package vod.asset;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 路线 B：几张只增不减的流水表按保留期删旧行。每张表每轮分批删，直到删完或到单轮上限。
 *
 * <ul>
 *   <li>vod_webhook_delivery：DELIVERED / FAILED 且创建早于 webhook 保留期。PENDING 永远不删。</li>
 *   <li>vod_webhook_event：DISPATCHED 且早于保留期、而且已没有任何投递行引用它。PENDING 不删。</li>
 *   <li>vod_outbox：PUBLISHED 且发布早于保留期。PENDING 不删。</li>
 *   <li>vod_idempotency_key：创建早于幂等保留期。之后同一个 Idempotency-Key 会被当成新请求。</li>
 * </ul>
 * 保留期 <= 0 表示这张表不清理。删除对多实例是安全的（同一行删两次第二次是 0 行）。
 */
@Component
public class RetentionCleanup {
    private static final Logger log = LoggerFactory.getLogger(RetentionCleanup.class);

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final long webhookDays;
    private final long outboxDays;
    private final long idempotencyHours;
    private final int batchSize;
    private final int maxBatches;

    /** 默认值只是开发默认，不是基准或承诺。 */
    public RetentionCleanup(JdbcTemplate jdbc, MeterRegistry registry,
                            @Value("${vod.retention.webhook-days:30}") long webhookDays,
                            @Value("${vod.retention.outbox-days:30}") long outboxDays,
                            @Value("${vod.retention.idempotency-hours:168}") long idempotencyHours,
                            @Value("${vod.retention.batch-size:500}") int batchSize,
                            @Value("${vod.retention.max-batches:20}") int maxBatches) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.webhookDays = webhookDays;
        this.outboxDays = outboxDays;
        this.idempotencyHours = idempotencyHours;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
    }

    @Scheduled(fixedDelayString = "${vod.retention.interval-ms:3600000}",
            initialDelayString = "${vod.retention.initial-delay-ms:60000}")
    public void scheduled() {
        try {
            var removed = runOnce();
            if (removed.values().stream().anyMatch(n -> n > 0)) log.info("retention cleanup removed {}", removed);
        } catch (RuntimeException e) {
            log.warn("retention cleanup pass failed: {}", e.toString());
        }
    }

    /** 一轮，返回各表删掉的行数（测试用）。 */
    public synchronized Map<String, Integer> runOnce() {
        long nowMs = System.currentTimeMillis();
        var out = new LinkedHashMap<String, Integer>();
        if (webhookDays > 0) {
            Timestamp cutoff = new Timestamp(nowMs - webhookDays * 86_400_000L);
            out.put("webhook_delivery", drain("webhook_delivery",
                    "DELETE FROM vod_webhook_delivery WHERE status IN ('DELIVERED', 'FAILED') AND created_at < ? LIMIT ?", cutoff));
            out.put("webhook_event", drain("webhook_event",
                    "DELETE FROM vod_webhook_event WHERE status = 'DISPATCHED' AND created_at < ? "
                            + "AND NOT EXISTS (SELECT 1 FROM vod_webhook_delivery d WHERE d.event_id = vod_webhook_event.id) LIMIT ?", cutoff));
        }
        if (outboxDays > 0) {
            Timestamp cutoff = new Timestamp(nowMs - outboxDays * 86_400_000L);
            out.put("outbox", drain("outbox",
                    "DELETE FROM vod_outbox WHERE status = 'PUBLISHED' AND published_at < ? LIMIT ?", cutoff));
        }
        if (idempotencyHours > 0) {
            Timestamp cutoff = new Timestamp(nowMs - idempotencyHours * 3_600_000L);
            out.put("idempotency_key", drain("idempotency_key",
                    "DELETE FROM vod_idempotency_key WHERE created_at < ? LIMIT ?", cutoff));
        }
        return out;
    }

    private int drain(String table, String sql, Timestamp cutoff) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int n = jdbc.update(sql, cutoff, batchSize);
            total += n;
            if (n < batchSize) break;
        }
        if (total > 0) registry.counter("vod.retention.deleted", "table", table).increment(total);
        return total;
    }
}
