package vod.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vod.quota.QuotaService;
import vod.template.TemplateService;
import vod.tenant.TenantGuard;
import vod.upload.UploadService;
import vod.usage.UsageService;
import vod.webhook.WebhookEvents;
import vod.webhook.WebhookService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

/**
 * Phase 5：按模板重新转码（POST /api/v1/assets/{id}/transcodes）与租户侧重试（POST /api/v1/jobs/{id}/retry）。
 * 两者都锁住同一资产的全部任务行，任意模板有进行中的任务就 409：同一资产同一时间只有一个任务在转。
 *
 * <p>都不拆 uk_asset_profile：一个资产 + 一个模板（profile）永远只有一行任务。终态任务被复用——同一事务里改回 PENDING、
 * claim_version+1、清租约 / 错误 / attempts，写一条新 outbox（消息 v1 不变）和一条 transcode.requested 事件。
 * Worker 领取时 claim_version 再 +1 并按 Phase 4 规则插入新的 WRITING 前缀 assets/v1/{asset}/{job}-{claim_version}/，
 * 成功事务把它切成 ACTIVE、旧 ACTIVE 变 GARBAGE；在此之前资产若是 READY 就保持 READY，继续播放旧版本。
 * 加密资产沿用 vod_content_key 里已有的 key（Worker INSERT IGNORE），本阶段不轮换。
 *
 * <p>加锁顺序：租户（配额）→ Idempotency-Key → 任务 → 资产，和取消、Worker 的任务 → 资产 → 前缀不成环。
 */
@Service
public class TranscodeService {
    private static final Set<String> ACTIVE = Set.of("PENDING", "RETRYING", "RUNNING");
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final QuotaService quotas;
    private final TemplateService templates;
    private final IdempotencyStore idempotency;
    private final OutboxWriter outbox;
    private final WebhookEvents events;
    private final UsageService usage;
    private final boolean retryCancelled;

    public TranscodeService(JdbcTemplate jdbc, QuotaService quotas, TemplateService templates, IdempotencyStore idempotency,
                            OutboxWriter outbox, WebhookEvents events, UsageService usage,
                            @Value("${vod.jobs.retry-allow-cancelled:true}") boolean retryCancelled) {
        this.jdbc = jdbc;
        this.quotas = quotas;
        this.templates = templates;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.events = events;
        this.usage = usage;
        this.retryCancelled = retryCancelled;
    }

    /** replayed=true 表示 Idempotency-Key 重放，没有新写入。 */
    public record Result(long jobId, boolean replayed) {}

    @Transactional
    public Result transcode(long tenantId, long assetId, String templateName, String idemKey, String traceId) {
        quotas.lockTenant(tenantId);
        var assets = jdbc.query("SELECT tenant_id, status, source_size FROM vod_media_asset WHERE id = ?",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getObject(3)}, assetId);
        if (assets.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        TenantGuard.requireVisible(tenantId, (Long) assets.get(0)[0]);
        // 路线 B：已删除的资产对租户不可见。
        if (vod.asset.AssetLifecycle.DELETED.equals(assets.get(0)[1])) throw new TenantGuard.ResourceNotVisibleException();
        TemplateService.Template template = templates.resolve(tenantId, templateName);
        var claim = idempotency.claim(tenantId, "transcode", idemKey, "asset:" + assetId + ":template:" + template.name());
        if (claim != null && claim.replayJobId() != null) return new Result(claim.replayJobId(), true);
        String assetStatus = (String) assets.get(0)[1];
        if ("UPLOADING".equals(assetStatus) || assets.get(0)[2] == null) {
            throw new UploadService.ConflictException("asset source is not uploaded yet");
        }
        // 锁住这个资产的全部任务行（任意模板）：同一资产同一时间只允许一个进行中的转码，不只按 (资产, 模板) 拦。
        // 同租户的建任务事务已被 lockTenant 串行；MySQL 下按 asset_id 的 next-key 锁也挡住并发插入。
        var all = jdbc.query("SELECT id, status, profile FROM vod_transcode_job WHERE asset_id = ? ORDER BY id FOR UPDATE",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}, assetId);
        for (Object[] other : all) {
            if (ACTIVE.contains((String) other[1])) {
                throw new UploadService.ConflictException("job " + other[0] + " (template " + other[2] + ") of this asset is "
                        + other[1] + ", wait for it to finish or cancel it");
            }
        }
        var jobs = all.stream().filter(j -> template.name().equals(j[2])).toList();
        long jobId;
        if (jobs.isEmpty()) {
            quotas.check(tenantId);
            try {
                jobId = insert("INSERT INTO vod_transcode_job(tenant_id, asset_id, profile, status, template_id) VALUES (?,?,?, 'PENDING', ?)",
                        tenantId, assetId, template.name(), template.id());
            } catch (DuplicateKeyException race) {
                throw new UploadService.ConflictException("a job for this asset and template was just created");
            }
        } else {
            jobId = (Long) jobs.get(0)[0];
            String status = (String) jobs.get(0)[1];
            if (!TERMINAL.contains(status)) throw new UploadService.ConflictException("job is " + status);
            quotas.check(tenantId);
            reset(jobId, status, template.id());
        }
        requeue(tenantId, jobId, assetId, traceId, "retranscode");
        idempotency.complete(claim, jobId);
        return new Result(jobId, false);
    }

    @Transactional
    public Result retry(long tenantId, long jobId, String idemKey, String traceId) {
        quotas.lockTenant(tenantId);
        // asset_id / tenant_id 建行后不变，这里先不加锁读，下面和 transcode 一样按 id 顺序锁住这个资产的全部任务行，
        // 加锁顺序保持「租户 → Idempotency-Key → 任务」。
        var owner = jdbc.query("SELECT tenant_id, asset_id FROM vod_transcode_job WHERE id = ?",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, jobId);
        if (owner.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        TenantGuard.requireVisible(tenantId, owner.get(0)[0]);
        var claim = idempotency.claim(tenantId, "retry", idemKey, "job:" + jobId);
        if (claim != null && claim.replayJobId() != null) return new Result(claim.replayJobId(), true);
        long assetId = owner.get(0)[1];
        var all = jdbc.query("SELECT id, status, profile, template_id FROM vod_transcode_job WHERE asset_id = ? ORDER BY id FOR UPDATE",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4)}, assetId);
        Object[] self = all.stream().filter(j -> (Long) j[0] == jobId).findFirst()
                .orElseThrow(TenantGuard.ResourceNotVisibleException::new);
        String status = (String) self[1];
        boolean allowed = "FAILED".equals(status) || (retryCancelled && "CANCELLED".equals(status));
        if (!allowed) {
            throw new UploadService.ConflictException("job is " + status + ", only FAILED"
                    + (retryCancelled ? " or CANCELLED" : "") + " jobs can be retried");
        }
        // 和 transcode 一致：同一资产任意模板还有进行中的任务就 409，不重置、不写 outbox
        for (Object[] other : all) {
            if ((Long) other[0] != jobId && ACTIVE.contains((String) other[1])) {
                throw new UploadService.ConflictException("job " + other[0] + " (template " + other[2] + ") of this asset is "
                        + other[1] + ", wait for it to finish or cancel it");
            }
        }
        // 路线 B：资产已删除时不能再重试（任务行保留作历史）。删除事务也先锁这些任务行，这里读到的状态是稳定的。
        String assetStatus = jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, assetId);
        if (vod.asset.AssetLifecycle.DELETED.equals(assetStatus)) {
            throw new UploadService.ConflictException("asset " + assetId + " has been deleted");
        }
        quotas.check(tenantId);
        Object templateId = self[3];
        reset(jobId, status, templateId == null ? null : ((Number) templateId).longValue());
        requeue(tenantId, jobId, assetId, traceId, "retry");
        idempotency.complete(claim, jobId);
        return new Result(jobId, false);
    }

    /** 与 scripts/dlq.py replay 同一套重置：以数据库为准，不转发死信原消息。 */
    private void reset(long jobId, String fromStatus, Long templateId) {
        int n = jdbc.update("UPDATE vod_transcode_job SET status = 'PENDING', claim_version = claim_version + 1, attempts = 0, "
                        + "error_code = NULL, last_error = NULL, locked_until = NULL, worker_id = NULL, next_attempt_at = NULL, "
                        + "template_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?",
                templateId, jobId, fromStatus);
        if (n != 1) throw new UploadService.ConflictException("job changed concurrently, retry the request");
    }

    private void requeue(long tenantId, long jobId, long assetId, String traceId, String reason) {
        // READY 资产保持 READY（旧版本继续可播，新前缀在成功事务里切换）；其余回到 UPLOADED。
        jdbc.update("UPDATE vod_media_asset SET status = 'UPLOADED' WHERE id = ? AND tenant_id = ? AND status <> 'READY'",
                assetId, tenantId);
        outbox.enqueue(tenantId, jobId, assetId, traceId);
        events.recordForJob(jobId, WebhookService.REQUESTED, reason);
        usage.addTranscode(tenantId);
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return UploadService.generatedId(keys);
    }

    static List<String> activeStatuses() {
        return List.copyOf(ACTIVE);
    }
}
