package vod.upload;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vod.storage.ObjectStorage;

import java.sql.Timestamp;
import java.util.List;

/**
 * Phase 6：过期仍 OPEN 的 multipart 会话 → 存储侧 abort（丢掉未完成分片）→ 会话 ABORTED。
 *
 * <p>只处理 upload_mode='multipart'、status='OPEN'、已过期的会话；COMPLETED 不碰，不删除任何对象
 * （包括已经组合好、但没建任务的源对象，以及 put 会话留下的对象，见 README 风险）。
 * 多实例：条件 UPDATE 写 cleanup_until 领取，领到才处理；存储失败只打日志，领取到期后下一轮再试。
 */
@Component
public class UploadCleanup {
    private static final Logger log = LoggerFactory.getLogger(UploadCleanup.class);

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final MeterRegistry registry;
    private final int batchSize;
    private final int claimSeconds;

    public UploadCleanup(JdbcTemplate jdbc, ObjectStorage storage, MeterRegistry registry,
                         @Value("${vod.upload.cleanup-batch-size:50}") int batchSize,
                         @Value("${vod.upload.cleanup-claim-seconds:300}") int claimSeconds) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.registry = registry;
        this.batchSize = Math.max(1, batchSize);
        this.claimSeconds = Math.max(1, claimSeconds);
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
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Long> candidates = jdbc.queryForList(
                "SELECT id FROM vod_upload_session WHERE status = 'OPEN' AND upload_mode = 'multipart' "
                        + "AND expires_at < ? AND (cleanup_until IS NULL OR cleanup_until < ?) ORDER BY id LIMIT ?",
                Long.class, now, now, batchSize);
        int done = 0;
        for (long id : candidates) {
            Timestamp until = new Timestamp(System.currentTimeMillis() + claimSeconds * 1000L);
            int claimed = jdbc.update(
                    "UPDATE vod_upload_session SET cleanup_until = ? WHERE id = ? AND status = 'OPEN' "
                            + "AND upload_mode = 'multipart' AND expires_at < ? AND (cleanup_until IS NULL OR cleanup_until < ?)",
                    until, id, now, now);
            if (claimed != 1) continue;  // 别的实例先领了，或会话刚被 complete / abort
            var rows = jdbc.query("SELECT object_key, multipart_upload_id FROM vod_upload_session WHERE id = ?",
                    (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, id);
            if (rows.isEmpty()) continue;
            String key = rows.get(0)[0];
            String uploadId = rows.get(0)[1];
            if (uploadId != null && key != null) {
                try {
                    storage.abortMultipart(UploadService.SOURCE_BUCKET, key, uploadId);
                } catch (RuntimeException e) {
                    registry.counter("vod.upload.multipart", "event", "cleanup_failed").increment();
                    log.warn("expired multipart abort failed session={}: {}", id, e.toString());
                    continue;  // 保留领取，到期后下一轮再试
                }
            }
            int changed = jdbc.update(
                    "UPDATE vod_upload_session SET status = 'ABORTED', cleanup_until = NULL, updated_at = ? "
                            + "WHERE id = ? AND status = 'OPEN'", new Timestamp(System.currentTimeMillis()), id);
            if (changed == 1) {
                done++;
                registry.counter("vod.upload.multipart", "event", "expired_aborted").increment();
            }
        }
        return done;
    }
}
