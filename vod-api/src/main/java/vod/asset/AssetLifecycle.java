package vod.asset;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vod.quota.QuotaService;
import vod.storage.ObjectStorage;
import vod.tenant.TenantGuard;
import vod.upload.UploadService;
import vod.usage.UsageService;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路线 B：资产删除与「上传没完成」的资产收尾。都是软删除：资产行保留、status='DELETED'，存储里的东西异步删。
 *
 * <ul>
 *   <li>转码产物：本资产所有 ACTIVE / WRITING 的输出前缀改成 GARBAGE，资产的 hls_prefix / poster_key 清空，
 *       之后由 Worker 现有的前缀清理在宽限期后删掉（宽限期内拿着旧签名地址的播放器和 CDN 缓存不会立刻断）。
 *       Phase 4 之前的固定前缀 assets/v1/{id}/ 没有前缀行，不会被删（README 已知边界）。</li>
 *   <li>源文件：vod_storage_object.delete_after 到期后由 {@link StorageObjectCleanup} 删除。</li>
 *   <li>存储配额：退回 source_size（complete 时占用的那部分）。</li>
 *   <li>内容密钥行、标签一并删除；播放、播放 token、密钥、转码、重试对 DELETED 资产都不可用。</li>
 * </ul>
 *
 * <p>加锁顺序：会话 → 租户 → 任务 → 资产 → 前缀。与 complete（会话 → 租户 → 对象 → 资产 → 任务）在会话行上串行；
 * 与建任务（租户 → 任务 → 资产）、取消和 Worker 成功事务（任务 → 资产 → 前缀）同序，不成环。
 */
@Service
public class AssetLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AssetLifecycle.class);
    public static final String DELETED = "DELETED";
    public static final String REASON_USER = "USER";
    public static final String REASON_UPLOAD_ABORTED = "UPLOAD_ABORTED";
    public static final String REASON_UPLOAD_EXPIRED = "UPLOAD_EXPIRED";
    private static final Set<String> ACTIVE_JOBS = Set.of("PENDING", "RETRYING", "RUNNING");

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final QuotaService quotas;
    private final UsageService usage;
    private final MeterRegistry registry;
    private final long sourceDeleteGraceSeconds;

    /** 默认值只是开发默认，不是基准或承诺。 */
    public AssetLifecycle(JdbcTemplate jdbc, ObjectStorage storage, QuotaService quotas, UsageService usage,
                          MeterRegistry registry,
                          @Value("${vod.asset.source-delete-grace-seconds:0}") long sourceDeleteGraceSeconds) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.quotas = quotas;
        this.usage = usage;
        this.registry = registry;
        this.sourceDeleteGraceSeconds = Math.max(0, sourceDeleteGraceSeconds);
    }

    /**
     * DELETE /api/v1/assets/{id}。跨租户 404；有进行中的任务（PENDING / RETRYING / RUNNING）409，先取消再删；
     * 已删除再调 200（幂等）。OPEN 的上传会话一并取消（multipart 在存储侧 abort，失败 503、什么都不改）。
     */
    @Transactional
    public Map<String, Object> delete(long tenantId, long assetId) {
        var owner = jdbc.query("SELECT tenant_id FROM vod_media_asset WHERE id = ?", (rs, n) -> rs.getLong(1), assetId);
        if (owner.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        TenantGuard.requireVisible(tenantId, owner.get(0));
        // 会话先锁：和 complete 在会话行上串行（complete 先锁会话再锁租户）。按主键逐行锁，不在 asset_id 上做锁定扫描。
        List<Long> sessionIds = jdbc.queryForList(
                "SELECT id FROM vod_upload_session WHERE asset_id = ? AND tenant_id = ? ORDER BY id", Long.class, assetId, tenantId);
        List<UploadService.Session> sessions = new java.util.ArrayList<>();
        for (long id : sessionIds) {
            sessions.addAll(jdbc.query("SELECT " + UploadService.SESSION_COLUMNS + " FROM vod_upload_session WHERE id = ? FOR UPDATE",
                    UploadService.SESSION_ROW, id));
        }
        quotas.lockTenant(tenantId);
        var jobs = jdbc.query("SELECT id, status FROM vod_transcode_job WHERE asset_id = ? ORDER BY id FOR UPDATE",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, assetId);
        var assets = jdbc.query(
                "SELECT status, source_size, source_object_id, deleted_at, deleted_reason FROM vod_media_asset WHERE id = ? FOR UPDATE",
                (rs, n) -> new Object[]{rs.getString(1), rs.getObject(2), rs.getObject(3), rs.getTimestamp(4), rs.getString(5)},
                assetId);
        String status = (String) assets.get(0)[0];
        if (DELETED.equals(status)) return deletedView(assetId, (Timestamp) assets.get(0)[3], (String) assets.get(0)[4]);
        for (Object[] job : jobs) {
            if (ACTIVE_JOBS.contains((String) job[1])) {
                throw new UploadService.ConflictException("job " + job[0] + " of this asset is " + job[1] + ", cancel it before deleting");
            }
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (UploadService.Session session : sessions) {
            if (!"OPEN".equals(session.status())) continue;
            if (session.multipart() && session.uploadId() != null) {
                try {
                    storage.abortMultipart(UploadService.SOURCE_BUCKET, session.objectKey(), session.uploadId());
                } catch (RuntimeException e) {
                    log.warn("delete asset={}: abort multipart failed session={}: {}", assetId, session.id(), e.toString());
                    throw new UploadService.StorageUnavailableException("object storage unavailable, retry later");
                }
            }
            jdbc.update("UPDATE vod_upload_session SET status = 'ABORTED', cleanup_until = NULL, updated_at = ? WHERE id = ? AND status = 'OPEN'",
                    now, session.id());
        }
        Object size = assets.get(0)[1];
        // source_size 只在 complete 成功时写，同一事务里占用了存储配额；没写过就没占用。
        if (size != null) usage.releaseStorage(tenantId, ((Number) size).longValue());
        markDeleted(tenantId, assetId, REASON_USER, now);
        return deletedView(assetId, now, REASON_USER);
    }

    /**
     * 上传没完成就结束（取消上传 / 过期清理）：资产标 DELETED、源对象排进删除。调用方已在事务里锁住会话行，
     * 并保证这个资产没有任务、没有占用存储配额（还没 complete 成功）。已是 DELETED 时不重复处理。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void abandonUpload(long tenantId, long assetId, String reason) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        var rows = jdbc.query("SELECT status FROM vod_media_asset WHERE id = ? AND tenant_id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), assetId, tenantId);
        if (rows.isEmpty() || DELETED.equals(rows.get(0))) return;
        markDeleted(tenantId, assetId, reason, now);
    }

    private void markDeleted(long tenantId, long assetId, String reason, Timestamp now) {
        jdbc.update("UPDATE vod_output_prefix SET status = 'GARBAGE', updated_at = ? WHERE asset_id = ? AND status IN ('ACTIVE', 'WRITING')",
                now, assetId);
        jdbc.update("UPDATE vod_media_asset SET status = 'DELETED', deleted_at = ?, deleted_reason = ?, updated_at = ?, "
                + "hls_prefix = NULL, poster_key = NULL WHERE id = ? AND tenant_id = ?", now, reason, now, assetId, tenantId);
        // 截到整秒：MySQL TIMESTAMP（无小数位）会把小数秒四舍五入，「现在」可能被存成下一秒，清理就要多等一轮。
        long due = now.getTime() + sourceDeleteGraceSeconds * 1000L;
        Timestamp deleteAfter = new Timestamp(due - Math.floorMod(due, 1000L));
        jdbc.update("UPDATE vod_storage_object SET delete_after = ? WHERE tenant_id = ? AND deleted_at IS NULL AND delete_after IS NULL "
                + "AND id = (SELECT source_object_id FROM vod_media_asset WHERE id = ?)", deleteAfter, tenantId, assetId);
        jdbc.update("DELETE FROM vod_content_key WHERE asset_id = ?", assetId);
        jdbc.update("DELETE FROM vod_asset_tag WHERE asset_id = ?", assetId);
        registry.counter("vod.asset.deleted", "reason", reason).increment();
    }

    private static Map<String, Object> deletedView(long assetId, Timestamp at, String reason) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", assetId);
        out.put("status", DELETED);
        out.put("deleted_at", at == null ? null : at.toInstant().toString());
        out.put("deleted_reason", reason);
        return out;
    }
}
