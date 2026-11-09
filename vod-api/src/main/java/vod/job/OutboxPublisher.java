package vod.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 兜底发布仍处于 PENDING 的 outbox 行。complete 提交后的快速路径负责低延迟，
 * 这里负责重试那些当时没有确认成功的行。
 *
 * <p>多实例：发布前先用条件 UPDATE 领取（locked_by / locked_until），领到的实例才发布；
 * 领取过期（实例崩溃、卡住）后别的实例可以接手。发布成功但标记前崩溃的行会被再次发布，
 * 这是至少一次投递，下游 Worker 按 job_id + claim 幂等。时间用 JVM 时钟，实例间时钟偏差应远小于领取时长。
 */
@Component
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final TranscodePublisher publisher;
    private final int batchSize;
    private final long maxBackoffSeconds;
    private final long claimSeconds;
    private final String instanceId;
    private final AtomicLong claims = new AtomicLong();

    /** 默认值只是开发默认，不是基准或承诺。 */
    public OutboxPublisher(JdbcTemplate jdbc, TranscodePublisher publisher,
                           @Value("${vod.outbox.batch-size:20}") int batchSize,
                           @Value("${vod.outbox.max-backoff-seconds:300}") long maxBackoffSeconds,
                           @Value("${vod.outbox.claim-seconds:60}") long claimSeconds) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.batchSize = Math.max(1, batchSize);
        this.maxBackoffSeconds = Math.max(1, maxBackoffSeconds);
        this.claimSeconds = Math.max(1, claimSeconds);
        this.instanceId = hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${vod.outbox.publish-interval-ms:5000}",
            initialDelayString = "${vod.outbox.publish-initial-delay-ms:5000}")
    public synchronized int publishPending() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Long> candidates = jdbc.queryForList(
                "SELECT id FROM vod_outbox "
                        + "WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "AND (locked_until IS NULL OR locked_until < ?) ORDER BY id LIMIT ?",
                Long.class, now, now, batchSize);
        int published = 0;
        for (long id : candidates) {
            // 每次领取用唯一 token：匹配到的行一定有变化，affected rows 语义不影响判断。
            String token = instanceId + ":" + claims.incrementAndGet();
            Timestamp until = new Timestamp(System.currentTimeMillis() + claimSeconds * 1000L);
            int claimed = jdbc.update(
                    "UPDATE vod_outbox SET locked_by = ?, locked_until = ? "
                            + "WHERE id = ? AND status = 'PENDING' AND (locked_until IS NULL OR locked_until < ?)",
                    token, until, id, now);
            if (claimed != 1) continue;  // 别的实例先领了
            List<Pending> rows = jdbc.query(
                    "SELECT payload, attempts FROM vod_outbox WHERE id = ? AND locked_by = ?",
                    (rs, n) -> new Pending(rs.getString("payload"), rs.getInt("attempts")), id, token);
            if (rows.isEmpty()) continue;
            Pending row = rows.get(0);
            if (publisher.publish(row.payload())) {
                jdbc.update("UPDATE vod_outbox SET status = 'PUBLISHED', published_at = ?, locked_by = NULL, locked_until = NULL "
                                + "WHERE id = ? AND status = 'PENDING' AND locked_by = ?",
                        new Timestamp(System.currentTimeMillis()), id, token);
                published++;
            } else {
                int attempts = row.attempts() + 1;
                long delaySeconds = Math.min(maxBackoffSeconds, 1L << Math.min(attempts, 30));
                Timestamp nextAttempt = new Timestamp(System.currentTimeMillis() + delaySeconds * 1000L);
                jdbc.update("UPDATE vod_outbox SET attempts = ?, next_attempt_at = ?, locked_by = NULL, locked_until = NULL "
                                + "WHERE id = ? AND status = 'PENDING' AND locked_by = ?",
                        attempts, nextAttempt, id, token);
            }
        }
        return published;
    }

    String instanceId() {
        return instanceId;
    }

    /** 容器里 HOSTNAME 是容器 id；截断保证 locked_by 不超过列宽。 */
    private static String hostname() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) host = "vod-api";
        return host.length() > 60 ? host.substring(0, 60) : host;
    }

    private record Pending(String payload, int attempts) {
    }
}
