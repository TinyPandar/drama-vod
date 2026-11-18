package vod.asset;

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
 * 路线 B：删除排进来的源对象（vod_storage_object.delete_after 到期、deleted_at 为空）。
 *
 * <p>删前复核：对象必须是 SOURCE、它所属的资产必须已经 DELETED（防止误删在用的源文件）。删除对存储是幂等的。
 * 多实例：条件 UPDATE 写 cleanup_until 领取；存储失败保留领取、到期后下一轮再试。转码产物不归这里，归 Worker 的前缀清理。
 */
@Component
public class StorageObjectCleanup {
    private static final Logger log = LoggerFactory.getLogger(StorageObjectCleanup.class);

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final MeterRegistry registry;
    private final int batchSize;
    private final int claimSeconds;

    /** 默认值只是开发默认，不是基准或承诺。 */
    public StorageObjectCleanup(JdbcTemplate jdbc, ObjectStorage storage, MeterRegistry registry,
                                @Value("${vod.asset.object-cleanup-batch-size:50}") int batchSize,
                                @Value("${vod.asset.object-cleanup-claim-seconds:300}") int claimSeconds) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.registry = registry;
        this.batchSize = Math.max(1, batchSize);
        this.claimSeconds = Math.max(1, claimSeconds);
    }

    @Scheduled(fixedDelayString = "${vod.asset.object-cleanup-interval-ms:60000}",
            initialDelayString = "${vod.asset.object-cleanup-initial-delay-ms:30000}")
    public void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("storage object cleanup pass failed: {}", e.toString());
        }
    }

    /** 一轮，返回本轮删掉的对象数（测试用）。 */
    public synchronized int runOnce() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Long> candidates = jdbc.queryForList(
                "SELECT o.id FROM vod_storage_object o JOIN vod_media_asset a ON a.source_object_id = o.id "
                        + "WHERE o.deleted_at IS NULL AND o.delete_after IS NOT NULL AND o.delete_after <= ? "
                        + "AND (o.cleanup_until IS NULL OR o.cleanup_until < ?) AND o.role = 'SOURCE' AND a.status = 'DELETED' "
                        + "ORDER BY o.id LIMIT ?",
                Long.class, now, now, batchSize);
        int done = 0;
        for (long id : candidates) {
            Timestamp until = new Timestamp(System.currentTimeMillis() + claimSeconds * 1000L);
            int claimed = jdbc.update("UPDATE vod_storage_object SET cleanup_until = ? WHERE id = ? AND deleted_at IS NULL "
                    + "AND (cleanup_until IS NULL OR cleanup_until < ?)", until, id, now);
            if (claimed != 1) continue;
            var rows = jdbc.query("SELECT o.bucket, o.object_key FROM vod_storage_object o "
                            + "JOIN vod_media_asset a ON a.source_object_id = o.id WHERE o.id = ? AND a.status = 'DELETED' AND o.role = 'SOURCE'",
                    (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, id);
            if (rows.isEmpty()) continue;
            try {
                storage.delete(rows.get(0)[0], rows.get(0)[1]);
            } catch (RuntimeException e) {
                registry.counter("vod.asset.source_delete", "result", "failed").increment();
                log.warn("delete source object failed id={}: {}", id, e.toString());
                continue;  // 保留领取，到期后下一轮再试
            }
            if (jdbc.update("UPDATE vod_storage_object SET deleted_at = ?, cleanup_until = NULL WHERE id = ? AND deleted_at IS NULL",
                    new Timestamp(System.currentTimeMillis()), id) == 1) {
                done++;
                registry.counter("vod.asset.source_delete", "result", "deleted").increment();
            }
        }
        return done;
    }
}
