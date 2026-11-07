package vod.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import vod.upload.UploadService;

@Service
public class WebhookService {
    private final JdbcTemplate jdbc;
    private final DnsLookup dns;
    private final WebhookPoster poster;

    public WebhookService(JdbcTemplate jdbc, DnsLookup dns, WebhookPoster poster) {
        this.jdbc = jdbc;
        this.dns = dns;
        this.poster = poster;
    }

    public Map<String, Object> register(long tenantId, String url, String events) {
        WebhookUrlGuard.requirePublicHttps(url);
        String secret = UUID.randomUUID().toString().replace("-", "");
        long id = insert("INSERT INTO vod_webhook_endpoint(tenant_id, url, secret, events) VALUES (?,?,?,?)",
                tenantId, url, secret, events == null || events.isBlank() ? "transcode.requested" : events);
        return Map.of("id", id, "url", url, "secret", secret);
    }

    public List<Map<String, Object>> list(long tenantId) {
        return jdbc.query("SELECT id, url, events, status FROM vod_webhook_endpoint WHERE tenant_id = ? ORDER BY id DESC",
                (rs, n) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "url", rs.getString("url"),
                        "events", rs.getString("events"),
                        "status", rs.getString("status")),
                tenantId);
    }

    public int dispatch(long tenantId, String eventType, String body) {
        var endpoints = jdbc.query(
                "SELECT id, url, secret, events FROM vod_webhook_endpoint WHERE tenant_id = ? AND status = 'ACTIVE'",
                (rs, n) -> new Endpoint(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                tenantId);
        int sent = 0;
        for (Endpoint endpoint : endpoints) {
            if (!endpoint.events.contains(eventType) && !endpoint.events.contains("*")) continue;
            int code = 0;
            String status = "FAILED";
            try {
                var uri = WebhookUrlGuard.requirePublicHttps(endpoint.url);
                WebhookUrlGuard.requireResolvedPublic(dns.lookup(uri.getHost()));
                code = poster.post(endpoint.url, body, WebhookSigner.sha256(endpoint.secret, body));
                status = code >= 200 && code < 300 ? "DELIVERED" : "FAILED";
                if (status.equals("DELIVERED")) sent++;
            } catch (RuntimeException ignored) {
                status = "BLOCKED";
            }
            jdbc.update("INSERT INTO vod_webhook_delivery(tenant_id, endpoint_id, event_type, status, attempts, response_code) "
                    + "VALUES (?,?,?,?,1,?)", tenantId, endpoint.id, eventType, status, code);
        }
        return sent;
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

    public record Endpoint(long id, String url, String secret, String events) {}

    public interface DnsLookup {
        List<InetAddress> lookup(String host);
    }

    public interface WebhookPoster {
        int post(String url, String body, String signature);
    }
}
