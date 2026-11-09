package vod.job;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务积压与 outbox 待发 gauge，定时从库里读（间隔走 vod.metrics.refresh-interval-ms）。
 * 只查非终态行，走 V9 的 (status, updated_at) / (status, created_at) 索引；等待秒数用库里的当前时间算，
 * 不受 JVM 与数据库时区差影响。查询失败时保留上一次的值并打日志。
 *
 * <p>Prometheus 名：vod_jobs_backlog{status}、vod_jobs_oldest_age_seconds{status}、
 * vod_outbox_pending、vod_outbox_oldest_pending_age_seconds。
 */
@Component
public class JobMetrics {
    static final List<String> BACKLOG = List.of("PENDING", "RETRYING", "RUNNING");
    static final List<String> WAITING = List.of("PENDING", "RETRYING");
    private static final Logger log = LoggerFactory.getLogger(JobMetrics.class);

    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> backlog = new LinkedHashMap<>();
    private final Map<String, AtomicLong> oldestAge = new LinkedHashMap<>();
    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxOldestAge = new AtomicLong();

    public JobMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String status : BACKLOG) {
            AtomicLong value = new AtomicLong();
            backlog.put(status, value);
            Gauge.builder("vod.jobs.backlog", value, AtomicLong::get)
                    .description("Core transcode jobs in a non-terminal status").tag("status", status).register(registry);
        }
        for (String status : WAITING) {
            AtomicLong value = new AtomicLong();
            oldestAge.put(status, value);
            Gauge.builder("vod.jobs.oldest.age.seconds", value, AtomicLong::get)
                    .description("seconds since the oldest job entered this status (RETRYING includes backoff)")
                    .tag("status", status).register(registry);
        }
        Gauge.builder("vod.outbox.pending", outboxPending, AtomicLong::get)
                .description("outbox rows not yet confirmed by the broker").register(registry);
        Gauge.builder("vod.outbox.oldest.pending.age.seconds", outboxOldestAge, AtomicLong::get)
                .description("seconds since the oldest pending outbox row was written").register(registry);
    }

    @Scheduled(fixedDelayString = "${vod.metrics.refresh-interval-ms:15000}",
            initialDelayString = "${vod.metrics.refresh-initial-delay-ms:5000}")
    public void refresh() {
        try {
            Map<String, long[]> seen = new LinkedHashMap<>();
            jdbc.query("SELECT status, COUNT(*) AS n, MIN(updated_at) AS oldest FROM vod_transcode_job "
                            + "WHERE status IN ('PENDING','RETRYING','RUNNING') GROUP BY status",
                    rs -> {
                        Timestamp oldest = rs.getTimestamp("oldest");
                        seen.put(rs.getString("status"),
                                new long[]{rs.getLong("n"), oldest == null ? Long.MIN_VALUE : oldest.getTime()});
                    });
            long[] outbox = jdbc.queryForObject(
                    "SELECT COUNT(*) AS n, MIN(created_at) AS oldest FROM vod_outbox WHERE status = 'PENDING'",
                    (rs, n) -> {
                        Timestamp oldest = rs.getTimestamp("oldest");
                        return new long[]{rs.getLong("n"), oldest == null ? Long.MIN_VALUE : oldest.getTime()};
                    });
            long now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).getTime();
            for (String status : BACKLOG) {
                long[] row = seen.get(status);
                backlog.get(status).set(row == null ? 0 : row[0]);
                if (oldestAge.containsKey(status)) oldestAge.get(status).set(ageSeconds(now, row == null ? Long.MIN_VALUE : row[1]));
            }
            outboxPending.set(outbox[0]);
            outboxOldestAge.set(ageSeconds(now, outbox[1]));
        } catch (DataAccessException e) {
            log.warn("job metrics refresh failed: {}", e.getMessage());
        }
    }

    private static long ageSeconds(long now, long since) {
        return since == Long.MIN_VALUE ? 0 : Math.max(0, (now - since) / 1000);
    }
}
