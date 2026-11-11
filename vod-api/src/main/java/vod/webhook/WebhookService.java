package vod.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import vod.tenant.TenantGuard;
import vod.upload.UploadService;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租户的 Webhook 端点：登记、列表、停用/启用，以及投递记录查询。签名密钥只在登记响应里出现一次，列表和投递记录都不返回。
 * 投递本身是异步的（{@link WebhookDispatcher}），这里不发 HTTP。
 */
@Service
public class WebhookService {
    public static final String REQUESTED = "transcode.requested";
    public static final String SUCCEEDED = "transcode.succeeded";
    public static final String FAILED = "transcode.failed";
    public static final String CANCELLED = "transcode.cancelled";
    public static final Set<String> EVENTS = Set.of(REQUESTED, SUCCEEDED, FAILED, CANCELLED);
    static final String DEFAULT_EVENTS = SUCCEEDED + "," + FAILED + "," + CANCELLED;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final RowMapper<Map<String, Object>> ENDPOINT_ROW = (rs, n) -> {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", rs.getLong("id"));
        out.put("url", rs.getString("url"));
        out.put("events", rs.getString("events"));
        out.put("status", rs.getString("status"));
        out.put("created_at", iso(rs.getTimestamp("created_at")));
        out.put("disabled_at", iso(rs.getTimestamp("disabled_at")));
        return out;
    };
    private static final RowMapper<Map<String, Object>> DELIVERY_ROW = (rs, n) -> {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", rs.getLong("id"));
        out.put("tenant_id", rs.getLong("tenant_id"));
        out.put("endpoint_id", rs.getLong("endpoint_id"));
        out.put("event_id", rs.getObject("event_id") == null ? null : rs.getLong("event_id"));
        out.put("event_type", rs.getString("event_type"));
        out.put("job_id", rs.getObject("job_id") == null ? null : rs.getLong("job_id"));
        out.put("status", rs.getString("status"));
        out.put("attempts", rs.getInt("attempts"));
        out.put("response_code", rs.getObject("response_code") == null ? null : rs.getInt("response_code"));
        out.put("last_error", rs.getString("last_error"));
        out.put("next_attempt_at", iso(rs.getTimestamp("next_attempt_at")));
        out.put("created_at", iso(rs.getTimestamp("created_at")));
        out.put("delivered_at", iso(rs.getTimestamp("delivered_at")));
        return out;
    };
    private static final String DELIVERY_SELECT = "SELECT d.id, d.tenant_id, d.endpoint_id, d.event_id, d.event_type, ev.job_id, "
            + "d.status, d.attempts, d.response_code, d.last_error, d.next_attempt_at, d.created_at, d.delivered_at "
            + "FROM vod_webhook_delivery d LEFT JOIN vod_webhook_event ev ON ev.id = d.event_id";

    private final JdbcTemplate jdbc;
    private final WebhookTargetPolicy policy;

    public WebhookService(JdbcTemplate jdbc, WebhookTargetPolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    /** 登记：URL 形状、DNS 解析结果都要过 SSRF 检查（投递时还会再查一次并钉住地址）。events 为空时订阅成功/失败/取消。 */
    public Map<String, Object> register(long tenantId, String url, Object events) {
        policy.resolve(url);
        String normalized = events(events);
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String secret = "whsec_" + HexFormat.of().formatHex(raw);
        long id = insert("INSERT INTO vod_webhook_endpoint(tenant_id, url, secret, events) VALUES (?,?,?,?)",
                tenantId, url.trim(), secret, normalized);
        var out = new LinkedHashMap<String, Object>(endpoint(tenantId, id));
        // 签名密钥只在这一次响应里出现。
        out.put("secret", secret);
        return out;
    }

    public List<Map<String, Object>> list(long tenantId) {
        return jdbc.query("SELECT id, url, events, status, created_at, disabled_at FROM vod_webhook_endpoint "
                + "WHERE tenant_id = ? ORDER BY id DESC", ENDPOINT_ROW, tenantId);
    }

    /** 停用：之后不再给它建投递，已排队的投递在发送前发现停用后标 SKIPPED。重复停用 200。 */
    public Map<String, Object> disable(long tenantId, long id) {
        endpoint(tenantId, id);
        jdbc.update("UPDATE vod_webhook_endpoint SET status = 'DISABLED', disabled_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'", id, tenantId);
        return endpoint(tenantId, id);
    }

    /** 重新启用前再过一遍 SSRF 检查。 */
    public Map<String, Object> enable(long tenantId, long id) {
        var current = endpoint(tenantId, id);
        policy.resolve((String) current.get("url"));
        jdbc.update("UPDATE vod_webhook_endpoint SET status = 'ACTIVE', disabled_at = NULL WHERE id = ? AND tenant_id = ?",
                id, tenantId);
        return endpoint(tenantId, id);
    }

    /** 投递记录。tenantId 为 null 时是管理端（可按 tenantFilter 过滤）；不返回 URL 的签名密钥。 */
    public List<Map<String, Object>> deliveries(Long tenantId, Long endpointId, Long jobId, int limit) {
        StringBuilder sql = new StringBuilder(DELIVERY_SELECT).append(" WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (tenantId != null) {
            sql.append(" AND d.tenant_id = ?");
            args.add(tenantId);
        }
        if (endpointId != null) {
            sql.append(" AND d.endpoint_id = ?");
            args.add(endpointId);
        }
        if (jobId != null) {
            sql.append(" AND ev.job_id = ?");
            args.add(jobId);
        }
        sql.append(" ORDER BY d.id DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbc.query(sql.toString(), DELIVERY_ROW, args.toArray());
    }

    private Map<String, Object> endpoint(long tenantId, long id) {
        var rows = jdbc.query("SELECT id, tenant_id, url, events, status, created_at, disabled_at FROM vod_webhook_endpoint WHERE id = ?",
                (rs, n) -> {
                    TenantGuard.requireVisible(tenantId, rs.getLong("tenant_id"));
                    return ENDPOINT_ROW.mapRow(rs, n);
                }, id);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        return rows.get(0);
    }

    /** 逗号分隔字符串或数组；只接受已知事件名或 *。 */
    static String events(Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) return DEFAULT_EVENTS;
        Set<String> out = new LinkedHashSet<>();
        Iterable<?> parts;
        if (raw instanceof String s) {
            parts = List.of(s.split(","));
        } else if (raw instanceof List<?> list) {
            parts = list;
        } else {
            throw new IllegalArgumentException("events must be an array or a comma separated string");
        }
        for (Object p : parts) {
            if (!(p instanceof String name)) throw new IllegalArgumentException("events must be strings");
            String e = name.trim();
            if (e.isEmpty()) continue;
            if (!e.equals("*") && !EVENTS.contains(e)) throw new IllegalArgumentException("unknown event: " + e);
            out.add(e);
        }
        if (out.isEmpty()) return DEFAULT_EVENTS;
        return String.join(",", out);
    }

    /** 端点是否订阅了该事件（逗号分隔、精确匹配，* 表示全部）。 */
    static boolean subscribed(String events, String eventType) {
        for (String part : events.split(",")) {
            String e = part.trim();
            if (e.equals("*") || e.equals(eventType)) return true;
        }
        return false;
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return UploadService.generatedId(keys);
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    public record PostResult(int status, String error) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public interface DnsLookup {
        List<InetAddress> lookup(String host);
    }

    /** 只连 address（已检查过的 IP），用 target 里的主机名做 SNI / 证书校验 / Host 头。 */
    public interface WebhookPoster {
        PostResult post(WebhookTargetPolicy.Target target, InetAddress address, String body, Map<String, String> headers);
    }
}
