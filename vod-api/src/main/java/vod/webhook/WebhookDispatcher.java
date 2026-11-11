package vod.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vod.ratelimit.RateLimiter;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Webhook 异步投递（Phase 5），结构照 {@link vod.job.OutboxPublisher}：条件 UPDATE 领取（locked_by / locked_until），
 * 领到的实例才处理，领取过期后别的实例接手，所以可以多实例。两步：
 *
 * <ol>
 *   <li>分发：领取 PENDING 事件，生成一次固定的 JSON payload，按订阅给每个 ACTIVE 端点插一条 PENDING 投递，
 *       事件改 DISPATCHED——同一事务；(event_id, endpoint_id) 唯一键防止重复分发。</li>
 *   <li>投递：领取到期的 PENDING 投递，过 SSRF 检查（重新解析并钉住地址）、按租户出站限速，发一次 POST。
 *       2xx 记 DELIVERED；否则 attempts+1，指数退避加 jitter 后再试，满 vod.webhook.max-attempts 次记 FAILED。
 *       端点已停用记 SKIPPED；被限速只推迟，不计次数。</li>
 * </ol>
 *
 * 不在任何数据库事务里发 HTTP。至少一次投递：发送成功但标记前崩溃的会再发一次，接收方按 X-Vod-Event-Id 去重。
 * 指标 vod_webhook_deliveries_total{result}，不带 URL、租户或密钥。
 */
@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebhookTargetPolicy policy;
    private final WebhookService.WebhookPoster poster;
    private final RateLimiter limiter;
    private final MeterRegistry registry;
    private final int batchSize;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final long maxBackoffSeconds;
    private final double jitter;
    private final long claimSeconds;
    private final String instanceId = "vod-api-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicLong claims = new AtomicLong();

    /** 默认值只是开发默认，不是基准或承诺。 */
    public WebhookDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, WebhookTargetPolicy policy,
                             WebhookService.WebhookPoster poster, RateLimiter limiter, MeterRegistry registry,
                             @Value("${vod.webhook.batch-size:20}") int batchSize,
                             @Value("${vod.webhook.max-attempts:8}") int maxAttempts,
                             @Value("${vod.webhook.base-backoff-seconds:10}") long baseBackoffSeconds,
                             @Value("${vod.webhook.max-backoff-seconds:3600}") long maxBackoffSeconds,
                             @Value("${vod.webhook.backoff-jitter:0.5}") double jitter,
                             @Value("${vod.webhook.claim-seconds:120}") long claimSeconds) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.policy = policy;
        this.poster = poster;
        this.limiter = limiter;
        this.registry = registry;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseBackoffSeconds = Math.max(1, baseBackoffSeconds);
        this.maxBackoffSeconds = Math.max(this.baseBackoffSeconds, maxBackoffSeconds);
        this.jitter = Math.min(1, Math.max(0, jitter));
        this.claimSeconds = Math.max(1, claimSeconds);
    }

    @Scheduled(fixedDelayString = "${vod.webhook.dispatch-interval-ms:2000}",
            initialDelayString = "${vod.webhook.dispatch-initial-delay-ms:5000}")
    public void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("webhook dispatch pass failed: {}", e.toString());
        }
    }

    /** 一轮：先分发事件，再投递到期的记录。返回本轮投递成功数（测试用）。 */
    public synchronized int runOnce() {
        fanOut();
        return deliverDue();
    }

    int fanOut() {
        Timestamp now = now();
        List<Long> ids = jdbc.queryForList("SELECT id FROM vod_webhook_event WHERE status = 'PENDING' "
                + "AND (locked_until IS NULL OR locked_until < ?) ORDER BY id LIMIT ?", Long.class, now, batchSize);
        int done = 0;
        for (long id : ids) {
            String token = token();
            int claimed = jdbc.update("UPDATE vod_webhook_event SET locked_by = ?, locked_until = ? "
                            + "WHERE id = ? AND status = 'PENDING' AND (locked_until IS NULL OR locked_until < ?)",
                    token, until(), id, now);
            if (claimed != 1) continue;
            try {
                Boolean ok = tx.execute(status -> dispatchEvent(id, token));
                if (Boolean.TRUE.equals(ok)) done++;
            } catch (DuplicateKeyException race) {
                // 领取过期后另一个实例已经分发过：事务回滚，什么都不改。
                log.info("webhook event {} already fanned out by another instance", id);
            }
        }
        return done;
    }

    private boolean dispatchEvent(long id, String token) {
        var rows = jdbc.query("SELECT tenant_id, event_type, job_id, asset_id, claim_version, error_code, reason, created_at "
                + "FROM vod_webhook_event WHERE id = ? AND locked_by = ? AND status = 'PENDING'", (rs, n) -> {
            var body = new LinkedHashMap<String, Object>();
            body.put("event_id", id);
            body.put("event_type", rs.getString("event_type"));
            Timestamp at = rs.getTimestamp("created_at");
            body.put("occurred_at", at == null ? null : at.toInstant().toString());
            body.put("tenant_id", rs.getLong("tenant_id"));
            body.put("job_id", rs.getObject("job_id") == null ? null : rs.getLong("job_id"));
            body.put("asset_id", rs.getObject("asset_id") == null ? null : rs.getLong("asset_id"));
            body.put("claim_version", rs.getObject("claim_version") == null ? null : rs.getInt("claim_version"));
            if (rs.getString("error_code") != null && WebhookService.FAILED.equals(rs.getString("event_type"))) {
                body.put("error_code", rs.getString("error_code"));
            }
            if (rs.getString("reason") != null) body.put("reason", rs.getString("reason"));
            return body;
        }, id, token);
        if (rows.isEmpty()) return false;
        var body = rows.get(0);
        String payload;
        try {
            payload = JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        long tenantId = (Long) body.get("tenant_id");
        String eventType = (String) body.get("event_type");
        var endpoints = jdbc.query("SELECT id, events FROM vod_webhook_endpoint WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY id",
                (rs, n) -> Map.entry(rs.getLong(1), rs.getString(2)), tenantId);
        Timestamp now = now();
        for (var endpoint : endpoints) {
            if (!WebhookService.subscribed(endpoint.getValue(), eventType)) continue;
            // next_attempt_at 留 NULL 表示立即到期：MySQL 的 TIMESTAMP 会把毫秒四舍五入到秒，写 now 可能比查询时的 now 还晚。
            jdbc.update("INSERT INTO vod_webhook_delivery(tenant_id, endpoint_id, event_type, status, attempts, event_id, "
                    + "created_at, updated_at) VALUES (?,?,?, 'PENDING', 0, ?,?,?)",
                    tenantId, endpoint.getKey(), eventType, id, now, now);
        }
        jdbc.update("UPDATE vod_webhook_event SET status = 'DISPATCHED', payload = ?, dispatched_at = ?, locked_by = NULL, "
                + "locked_until = NULL WHERE id = ? AND locked_by = ?", payload, now, id, token);
        return true;
    }

    int deliverDue() {
        Timestamp now = now();
        List<Long> ids = jdbc.queryForList("SELECT id FROM vod_webhook_delivery WHERE status = 'PENDING' AND event_id IS NOT NULL "
                + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) AND (locked_until IS NULL OR locked_until < ?) "
                + "ORDER BY id LIMIT ?", Long.class, now, now, batchSize);
        int delivered = 0;
        for (long id : ids) {
            String token = token();
            int claimed = jdbc.update("UPDATE vod_webhook_delivery SET locked_by = ?, locked_until = ? WHERE id = ? "
                    + "AND status = 'PENDING' AND (locked_until IS NULL OR locked_until < ?)", token, until(), id, now);
            if (claimed != 1) continue;
            if (deliver(id, token)) delivered++;
        }
        return delivered;
    }

    private record Claimed(long tenantId, long endpointId, long eventId, String eventType, int attempts, String url,
                           String secret, String endpointStatus, String payload) {}

    private boolean deliver(long id, String token) {
        var rows = jdbc.query("SELECT d.tenant_id, d.endpoint_id, d.event_id, d.event_type, d.attempts, e.url, e.secret, "
                        + "e.status AS endpoint_status, ev.payload FROM vod_webhook_delivery d "
                        + "JOIN vod_webhook_endpoint e ON e.id = d.endpoint_id JOIN vod_webhook_event ev ON ev.id = d.event_id "
                        + "WHERE d.id = ? AND d.locked_by = ?",
                (rs, n) -> new Claimed(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), id, token);
        if (rows.isEmpty()) return false;
        Claimed d = rows.get(0);
        if (!"ACTIVE".equals(d.endpointStatus())) {
            finish(id, token, "SKIPPED", d.attempts(), null, "endpoint disabled", null);
            count("skipped");
            return false;
        }
        RateLimiter.Decision allowed = limiter.tryAcquire("webhook", Long.toString(d.tenantId()));
        if (!allowed.allowed()) {
            // 被限速：推迟，不计次数。
            jdbc.update("UPDATE vod_webhook_delivery SET next_attempt_at = ?, locked_by = NULL, locked_until = NULL "
                    + "WHERE id = ? AND locked_by = ?", ts(System.currentTimeMillis() + allowed.retryAfterSeconds() * 1000L), id, token);
            count("rate_limited");
            return false;
        }
        int attempt = d.attempts() + 1;
        WebhookService.PostResult result;
        try {
            WebhookTargetPolicy.Target target = policy.resolve(d.url());
            InetAddress pinned = target.addresses().get(0);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Vod-Signature", WebhookSigner.sha256(d.secret(), d.payload()));
            headers.put("X-Vod-Event", d.eventType());
            headers.put("X-Vod-Event-Id", Long.toString(d.eventId()));
            headers.put("X-Vod-Delivery-Id", Long.toString(id));
            headers.put("X-Vod-Delivery-Attempt", Integer.toString(attempt));
            result = poster.post(target, pinned, d.payload(), headers);
        } catch (IllegalArgumentException blocked) {
            result = new WebhookService.PostResult(0, "blocked: " + blocked.getMessage());
            count("blocked");
        }
        if (result.ok()) {
            finish(id, token, "DELIVERED", attempt, result.status(), null, now());
            count("delivered");
            return true;
        }
        String error = result.error() != null ? result.error() : "http " + result.status();
        if (attempt >= maxAttempts) {
            finish(id, token, "FAILED", attempt, result.status() == 0 ? null : result.status(), error, null);
            count("failed");
        } else {
            jdbc.update("UPDATE vod_webhook_delivery SET attempts = ?, response_code = ?, last_error = ?, next_attempt_at = ?, "
                            + "updated_at = ?, locked_by = NULL, locked_until = NULL WHERE id = ? AND locked_by = ?",
                    attempt, result.status() == 0 ? null : result.status(), truncate(error), ts(System.currentTimeMillis()
                            + backoffSeconds(attempt) * 1000L), now(), id, token);
            count("retry");
        }
        return false;
    }

    private void finish(long id, String token, String status, int attempts, Integer code, String error, Timestamp deliveredAt) {
        jdbc.update("UPDATE vod_webhook_delivery SET status = ?, attempts = ?, response_code = ?, last_error = ?, delivered_at = ?, "
                        + "next_attempt_at = NULL, updated_at = ?, locked_by = NULL, locked_until = NULL WHERE id = ? AND locked_by = ?",
                status, attempts, code, truncate(error), deliveredAt, now(), id, token);
    }

    /** 第 attempt 次失败后的等待：min(max, base * 2^(attempt-1))，再按 jitter 比例随机缩短。 */
    long backoffSeconds(int attempt) {
        long ceiling = Math.min(maxBackoffSeconds, baseBackoffSeconds * (1L << Math.min(Math.max(0, attempt - 1), 30)));
        double factor = 1 - jitter * ThreadLocalRandom.current().nextDouble();
        return Math.max(1, (long) Math.ceil(ceiling * factor));
    }

    private void count(String result) {
        registry.counter("vod.webhook.deliveries", "result", result).increment();
    }

    private String token() {
        return instanceId + ":" + claims.incrementAndGet();
    }

    private Timestamp until() {
        return ts(System.currentTimeMillis() + claimSeconds * 1000L);
    }

    private static Timestamp now() {
        return ts(System.currentTimeMillis());
    }

    private static Timestamp ts(long millis) {
        return new Timestamp(millis);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
