package vod.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流（Phase 5）：租户 API（按租户）、管理登录（按客户端 IP）、出站 Webhook（按租户）。
 *
 * <p>配了 vod.ratelimit.redis-url（compose 里是 REDIS_URL）就用 Redis + Lua 计数，多实例 vod-api 共享额度；
 * 没配，或 Redis 连不上时退回进程内存桶——<b>内存桶只对单实例有效</b>，多实例时每个实例各算各的。
 * Redis 出错不拒绝请求（fail-open 到内存桶），只打限频日志。速率与突发都是配置项，默认值只是开发默认，不是基准或承诺。
 * 指标 vod_ratelimit_rejected_total{scope}，不带租户、IP 标签。
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String SCRIPT = String.join("\n",
            "local rate = tonumber(ARGV[1])",
            "local burst = tonumber(ARGV[2])",
            "local t = redis.call('TIME')",
            "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
            "local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')",
            "local tokens = tonumber(data[1])",
            "local ts = tonumber(data[2])",
            "if tokens == nil or ts == nil then tokens = burst ts = now end",
            "tokens = math.min(burst, tokens + math.max(0, now - ts) * rate / 1000)",
            "local allowed = 0",
            "local wait = 0",
            "if tokens >= 1 then tokens = tokens - 1 allowed = 1 else wait = math.ceil((1 - tokens) * 1000 / rate) end",
            "redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))",
            "redis.call('PEXPIRE', KEYS[1], math.ceil(burst * 1000 / rate) + 1000)",
            "return {allowed, wait}");

    public record Limit(double ratePerSecond, int burst) {
        public boolean unlimited() {
            return ratePerSecond <= 0 || burst <= 0;
        }
    }

    /** retryAfterSeconds 只在拒绝时有意义，至少 1 秒（Retry-After 头只能写整数秒）。 */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision OK = new Decision(true, 0);
    }

    private final boolean enabled;
    private final Map<String, Limit> limits;
    private final String redisUrl;
    private final long redisTimeoutMs;
    private final MeterRegistry registry;
    private final Map<String, Bucket> memory = new ConcurrentHashMap<>();
    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile long redisRetryAt;
    private volatile long lastWarnAt;

    public RateLimiter(MeterRegistry registry,
                       @Value("${vod.ratelimit.enabled:true}") boolean enabled,
                       @Value("${vod.ratelimit.redis-url:}") String redisUrl,
                       @Value("${vod.ratelimit.redis-timeout-ms:200}") long redisTimeoutMs,
                       @Value("${vod.ratelimit.tenant.rate-per-second:50}") double tenantRate,
                       @Value("${vod.ratelimit.tenant.burst:200}") int tenantBurst,
                       @Value("${vod.ratelimit.login.rate-per-second:0.2}") double loginRate,
                       @Value("${vod.ratelimit.login.burst:10}") int loginBurst,
                       @Value("${vod.ratelimit.webhook.rate-per-second:5}") double webhookRate,
                       @Value("${vod.ratelimit.webhook.burst:20}") int webhookBurst) {
        this.registry = registry;
        this.enabled = enabled;
        this.redisUrl = redisUrl == null ? "" : redisUrl.trim();
        this.redisTimeoutMs = Math.max(10, redisTimeoutMs);
        this.limits = Map.of(
                "tenant", new Limit(tenantRate, tenantBurst),
                "login", new Limit(loginRate, loginBurst),
                "webhook", new Limit(webhookRate, webhookBurst));
        if (enabled && this.redisUrl.isEmpty()) {
            log.info("rate limiting uses in-process buckets (no vod.ratelimit.redis-url): limits apply per vod-api instance only");
        }
    }

    public boolean usesRedis() {
        return !redisUrl.isEmpty();
    }

    /** scope 是 tenant / login / webhook；id 是租户 id 或客户端 IP（只进 Redis 键，不进指标和日志）。 */
    public Decision tryAcquire(String scope, String id) {
        if (!enabled) return Decision.OK;
        Limit limit = limits.get(scope);
        if (limit == null || limit.unlimited()) return Decision.OK;
        String key = "vod:rl:" + scope + ":" + id;
        Decision d = null;
        if (!redisUrl.isEmpty()) d = redis(key, limit);
        if (d == null) d = memory(key, limit);
        if (!d.allowed()) registry.counter("vod.ratelimit.rejected", "scope", scope).increment();
        return d;
    }

    private Decision redis(String key, Limit limit) {
        long now = System.currentTimeMillis();
        if (now < redisRetryAt) return null;
        try {
            StatefulRedisConnection<String, String> conn = connection();
            List<Object> out = conn.sync().eval(SCRIPT, ScriptOutputType.MULTI, new String[]{key},
                    Double.toString(limit.ratePerSecond()), Integer.toString(limit.burst()));
            boolean allowed = ((Number) out.get(0)).longValue() == 1;
            long waitMs = ((Number) out.get(1)).longValue();
            return allowed ? Decision.OK : new Decision(false, Math.max(1, (waitMs + 999) / 1000));
        } catch (RuntimeException e) {
            redisRetryAt = now + 5000;
            closeQuietly();
            if (now - lastWarnAt > 60_000) {
                lastWarnAt = now;
                log.warn("redis rate limiter unavailable, falling back to in-process buckets: {}", e.getClass().getSimpleName());
            }
            return null;
        }
    }

    private synchronized StatefulRedisConnection<String, String> connection() {
        if (connection != null && connection.isOpen()) return connection;
        if (client == null) {
            client = RedisClient.create(redisUrl);
            client.setDefaultTimeout(Duration.ofMillis(redisTimeoutMs));
        }
        connection = client.connect();
        connection.setTimeout(Duration.ofMillis(redisTimeoutMs));
        return connection;
    }

    private synchronized void closeQuietly() {
        try {
            if (connection != null) connection.close();
        } catch (RuntimeException ignored) {
            // 连接本来就坏了
        }
        connection = null;
    }

    private Decision memory(String key, Limit limit) {
        if (memory.size() > 100_000) memory.clear();  // 防止大量不同 IP 撑爆内存；清空只会让额度暂时变松
        Bucket bucket = memory.computeIfAbsent(key, k -> new Bucket(limit.burst()));
        return bucket.take(limit, System.nanoTime());
    }

    @PreDestroy
    void shutdown() {
        closeQuietly();
        if (client != null) client.shutdown();
    }

    private static final class Bucket {
        private double tokens;
        private long ts;

        Bucket(int burst) {
            this.tokens = burst;
            this.ts = System.nanoTime();
        }

        synchronized Decision take(Limit limit, long now) {
            double elapsedSeconds = Math.max(0, now - ts) / 1e9;
            tokens = Math.min(limit.burst(), tokens + elapsedSeconds * limit.ratePerSecond());
            ts = now;
            if (tokens >= 1) {
                tokens -= 1;
                return Decision.OK;
            }
            double wait = (1 - tokens) / limit.ratePerSecond();
            return new Decision(false, Math.max(1, (long) Math.ceil(wait)));
        }
    }
}
