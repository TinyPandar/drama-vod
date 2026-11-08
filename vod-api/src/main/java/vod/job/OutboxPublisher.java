package vod.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

/**
 * 兜底发布仍处于 PENDING 的 outbox 行。complete 提交后的快速路径负责低延迟，
 * 这里负责重试那些当时没有确认成功的行。单实例即可，不需要分布式锁。
 */
@Component
public class OutboxPublisher {
    private static final int BATCH_SIZE = 20;
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final JdbcTemplate jdbc;
    private final TranscodePublisher publisher;

    public OutboxPublisher(JdbcTemplate jdbc, TranscodePublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${vod.outbox.publish-interval-ms:5000}",
            initialDelayString = "${vod.outbox.publish-initial-delay-ms:5000}")
    public synchronized int publishPending() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Pending> rows = jdbc.query(
                "SELECT id, payload, attempts FROM vod_outbox "
                        + "WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
                        + "ORDER BY id LIMIT ?",
                (rs, n) -> new Pending(rs.getLong("id"), rs.getString("payload"), rs.getInt("attempts")),
                now, BATCH_SIZE);
        int published = 0;
        for (Pending row : rows) {
            if (publisher.publish(row.payload())) {
                jdbc.update("UPDATE vod_outbox SET status = 'PUBLISHED', published_at = ? WHERE id = ? AND status = 'PENDING'",
                        new Timestamp(System.currentTimeMillis()), row.id());
                published++;
            } else {
                int attempts = row.attempts() + 1;
                long delaySeconds = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempts, 8));
                Timestamp nextAttempt = new Timestamp(System.currentTimeMillis() + delaySeconds * 1000L);
                jdbc.update("UPDATE vod_outbox SET attempts = ?, next_attempt_at = ? WHERE id = ? AND status = 'PENDING'",
                        attempts, nextAttempt, row.id());
            }
        }
        return published;
    }

    private record Pending(long id, String payload, int attempts) {
    }
}
