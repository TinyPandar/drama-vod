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
        TemplateService.Template template = templates.resolve(tenantId, templateName);
        var claim = idempotency.claim(tenantId, "transcode", idemKey, "asset:" + assetId + ":template:" + template.name());
        if (claim != null && claim.replayJobId() != null) return new Result(claim.replayJobId(), true);
        String assetStatus = (String) assets.get(0)[1];
        if ("UPLOADING".equals(assetStatus) || assets.get(0)[2] == null) {
            throw new UploadService.ConflictException("asset source is not uploaded yet");
        }
        var jobs = jdbc.query("SELECT id, status FROM vod_transcode_job WHERE asset_id = ? AND profile = ? FOR UPDATE",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, assetId, template.name());
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
            if (ACTIVE.contains(status)) throw new UploadService.ConflictException("job is " + status + ", wait for it to finish or cancel it");
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
        var jobs = jdbc.query("SELECT tenant_id, asset_id, status, template_id FROM vod_transcode_job WHERE id = ? FOR UPDATE",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4)}, jobId);
        if (jobs.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        TenantGuard.requireVisible(tenantId, (Long) jobs.get(0)[0]);
        var claim = idempotency.claim(tenantId, "retry", idemKey, "job:" + jobId);
        if (claim != null && claim.replayJobId() != null) return new Result(claim.replayJobId(), true);
        long assetId = (Long) jobs.get(0)[1];
        String status = (String) jobs.get(0)[2];
        boolean allowed = "FAILED".equals(status) || (retryCancelled && "CANCELLED".equals(status));
        if (!allowed) {
            throw new UploadService.ConflictException("job is " + status + ", only FAILED"
                    + (retryCancelled ? " or CANCELLED" : "") + " jobs can be retried");
        }
        quotas.check(tenantId);
        Object templateId = jobs.get(0)[3];
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
