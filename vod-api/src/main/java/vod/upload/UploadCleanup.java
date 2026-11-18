package vod.upload;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vod.asset.AssetLifecycle;
import vod.storage.ObjectStorage;

import java.sql.Timestamp;
import java.util.List;

/**
 * 过期仍 OPEN 的上传会话收尾。会话改 ABORTED，资产改 DELETED（原因 UPLOAD_EXPIRED），源对象排进
 * {@link vod.asset.StorageObjectCleanup} 删除。三种情况：
 *
 * <ul>
 *   <li>multipart、存储侧还没组合：先在存储侧 abort（丢掉已传分片），再收尾。（Phase 6 原有行为，加上了资产与对象的收尾。）</li>
 *   <li>multipart、存储侧已组合但一直没建任务（N1：例如配额 403 后客户端没再重试 complete）：过期后再等
 *       {@code vod.upload.assembled-retention-seconds}，期间仍允许 complete；超过后收尾，源对象删除。</li>
 *   <li>put：过期后再等 {@code vod.upload.put-abandon-grace-seconds}（给过期前已开始的 PUT 留时间）后收尾。
 *       put 会话过期后 complete 本来就 409，桶里留下的对象已不可能被用到。</li>
 * </ul>
 * 有任务的会话不碰（正常不会出现：建任务和会话 COMPLETED 在同一事务）。
 * 多实例：条件 UPDATE 写 cleanup_until 领取，领到才处理；存储失败只打日志，领取到期后下一轮再试。
 */
@Component
public class UploadCleanup {
    private static final Logger log = LoggerFactory.getLogger(UploadCleanup.class);

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final MeterRegistry registry;
    private final AssetLifecycle lifecycle;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final int claimSeconds;
    private final long assembledRetentionSeconds;
    private final long putGraceSeconds;

    /** 默认值只是开发默认，不是基准或承诺。 */
    public UploadCleanup(JdbcTemplate jdbc, ObjectStorage storage, MeterRegistry registry, AssetLifecycle lifecycle,
                         TransactionTemplate tx,
                         @Value("${vod.upload.cleanup-batch-size:50}") int batchSize,
                         @Value("${vod.upload.cleanup-claim-seconds:300}") int claimSeconds,
                         @Value("${vod.upload.assembled-retention-seconds:604800}") long assembledRetentionSeconds,
                         @Value("${vod.upload.put-abandon-grace-seconds:300}") long putGraceSeconds) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.tx = tx;
        this.batchSize = Math.max(1, batchSize);
        this.claimSeconds = Math.max(1, claimSeconds);
        this.assembledRetentionSeconds = Math.max(0, assembledRetentionSeconds);
        this.putGraceSeconds = Math.max(0, putGraceSeconds);
    }

    @Scheduled(fixedDelayString = "${vod.upload.cleanup-interval-ms:60000}",
            initialDelayString = "${vod.upload.cleanup-initial-delay-ms:30000}")
    public void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("upload cleanup pass failed: {}", e.toString());
        }
    }

    /** 一轮，返回本轮打成 ABORTED 的会话数（测试用）。 */
    public synchronized int runOnce() {
        long nowMs = System.currentTimeMillis();
        Timestamp now = new Timestamp(nowMs);
        Timestamp putCutoff = new Timestamp(nowMs - putGraceSeconds * 1000L);
        // put 会话按「过期 + 宽限」挑，multipart 只要过期就挑（是否已组合要看存储）。
        List<Long> candidates = jdbc.queryForList(
                "SELECT id FROM vod_upload_session WHERE status = 'OPEN' "
                        + "AND ((upload_mode = 'multipart' AND expires_at < ?) OR (upload_mode = 'put' AND expires_at < ?)) "
                        + "AND (cleanup_until IS NULL OR cleanup_until < ?) ORDER BY id LIMIT ?",
                Long.class, now, putCutoff, now, batchSize);
        int done = 0;
        for (long id : candidates) {
            Timestamp until = new Timestamp(System.currentTimeMillis() + claimSeconds * 1000L);
            int claimed = jdbc.update(
                    "UPDATE vod_upload_session SET cleanup_until = ? WHERE id = ? AND status = 'OPEN' "
                            + "AND expires_at < ? AND (cleanup_until IS NULL OR cleanup_until < ?)",
                    until, id, now, now);
            if (claimed != 1) continue;  // 别的实例先领了，或会话刚被 complete / abort
            var rows = jdbc.query("SELECT tenant_id, asset_id, object_key, multipart_upload_id, upload_mode, expires_at "
                            + "FROM vod_upload_session WHERE id = ?",
                    (rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getTimestamp(6)), id);
            if (rows.isEmpty()) continue;
            Row row = rows.get(0);
            Integer jobs = jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job WHERE asset_id = ?", Integer.class, row.assetId());
            if (jobs != null && jobs > 0) continue;  // 不该出现；保留领取，不动它
            if (UploadService.MODE_MULTIPART.equals(row.mode()) && !prepareMultipart(id, row, nowMs)) continue;
            if (finish(id, row)) {
                done++;
                registry.counter("vod.upload.multipart", "event",
                        UploadService.MODE_MULTIPART.equals(row.mode()) ? "expired_aborted" : "put_expired").increment();
            }
        }
        return done;
    }

    /** multipart：已组合的按保留期决定是否收尾；没组合的在存储侧 abort。返回 false 表示这轮先不收尾（保留领取）。 */
    private boolean prepareMultipart(long id, Row row, long nowMs) {
        if (row.key() == null) return true;
        boolean assembled;
        try {
            assembled = storage.head(UploadService.SOURCE_BUCKET, row.key()).isPresent();
        } catch (RuntimeException e) {
            registry.counter("vod.upload.multipart", "event", "cleanup_failed").increment();
            log.warn("expired multipart head failed session={}: {}", id, e.toString());
            return false;
        }
        if (assembled) {
            // N1：存储侧已组合（建任务那步失败过）。保留期内仍让客户端重试 complete；过了保留期就收尾、删对象。
            long retentionEnd = row.expiresAt().getTime() + assembledRetentionSeconds * 1000L;
            if (nowMs < retentionEnd) {
                registry.counter("vod.upload.multipart", "event", "cleanup_skipped_assembled").increment();
                log.info("expired multipart session already assembled, keep until retention ends session={}", id);
                return false;
            }
            registry.counter("vod.upload.multipart", "event", "assembled_expired").increment();
            return true;
        }
        if (row.uploadId() != null) {
            try {
                storage.abortMultipart(UploadService.SOURCE_BUCKET, row.key(), row.uploadId());
            } catch (RuntimeException e) {
                registry.counter("vod.upload.multipart", "event", "cleanup_failed").increment();
                log.warn("expired multipart abort failed session={}: {}", id, e.toString());
                return false;
            }
        }
        return true;
    }

    private boolean finish(long id, Row row) {
        Boolean changed = tx.execute(status -> {
            // 带 status='OPEN' 的条件更新：期间被 complete / abort 的会话不会被覆盖。
            int n = jdbc.update("UPDATE vod_upload_session SET status = 'ABORTED', cleanup_until = NULL, updated_at = ? "
                    + "WHERE id = ? AND status = 'OPEN'", new Timestamp(System.currentTimeMillis()), id);
            if (n != 1) return false;
            lifecycle.abandonUpload(row.tenantId(), row.assetId(), AssetLifecycle.REASON_UPLOAD_EXPIRED);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private record Row(long tenantId, long assetId, String key, String uploadId, String mode, Timestamp expiresAt) {
    }
}
