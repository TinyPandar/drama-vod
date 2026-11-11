package vod.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vod.auth.ApiKeys;
import vod.upload.UploadService;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 管理端：租户（创建、列表、启用/停用、配额）与 API Key（创建、列表、撤销、轮换）。
 * Key 明文只出现在创建/轮换的响应里，库里只存 SHA-256 与前几位用于辨认；列表从不返回哈希或明文。
 * 停用租户不改 key 行，{@link vod.auth.ApiKeyFilter} 校验时连带看租户状态，所以重新启用后原 key 恢复可用。
 */
@Service
public class TenantAdminService {
    public static final Set<String> SCOPES = Set.of("upload", "asset", "job", "*");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _.-]{1,100}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final RowMapper<Map<String, Object>> TENANT_ROW = (rs, n) -> {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", rs.getLong("id"));
        out.put("name", rs.getString("name"));
        out.put("status", rs.getString("status"));
        out.put("storage_quota_bytes", rs.getObject("storage_quota_bytes") == null ? null : rs.getLong("storage_quota_bytes"));
        out.put("storage_used_bytes", rs.getLong("storage_used_bytes"));
        out.put("max_concurrent_jobs", rs.getObject("max_concurrent_jobs") == null ? null : rs.getInt("max_concurrent_jobs"));
        out.put("max_daily_transcodes", rs.getObject("max_daily_transcodes") == null ? null : rs.getInt("max_daily_transcodes"));
        out.put("active_keys", rs.getInt("active_keys"));
        out.put("created_at", iso(rs.getTimestamp("created_at")));
        return out;
    };
    private static final RowMapper<Map<String, Object>> KEY_ROW = (rs, n) -> {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", rs.getLong("id"));
        out.put("tenant_id", rs.getLong("tenant_id"));
        out.put("name", rs.getString("name"));
        out.put("key_prefix", rs.getString("key_prefix"));
        out.put("scopes", rs.getString("scopes"));
        out.put("status", rs.getString("status"));
        out.put("expires_at", iso(rs.getTimestamp("expires_at")));
        out.put("created_at", iso(rs.getTimestamp("created_at")));
        out.put("revoked_at", iso(rs.getTimestamp("revoked_at")));
        return out;
    };
    private static final String TENANT_SELECT = "SELECT t.id, t.name, t.status, t.storage_quota_bytes, t.storage_used_bytes, "
            + "t.max_concurrent_jobs, t.max_daily_transcodes, t.created_at, "
            + "(SELECT COUNT(*) FROM vod_api_credential c WHERE c.tenant_id = t.id AND c.status = 'ACTIVE' "
            + "AND (c.expires_at IS NULL OR c.expires_at > CURRENT_TIMESTAMP)) AS active_keys FROM vod_tenant t";

    private final JdbcTemplate jdbc;
    private final long maxKeyTtlSeconds;

    public TenantAdminService(JdbcTemplate jdbc, @Value("${vod.admin.max-key-ttl-seconds:0}") long maxKeyTtlSeconds) {
        this.jdbc = jdbc;
        this.maxKeyTtlSeconds = maxKeyTtlSeconds;
    }

    public List<Map<String, Object>> tenants() {
        return jdbc.query(TENANT_SELECT + " ORDER BY t.id", TENANT_ROW);
    }

    public Map<String, Object> tenant(long id) {
        var rows = jdbc.query(TENANT_SELECT + " WHERE t.id = ?", TENANT_ROW, id);
        if (rows.isEmpty()) throw new NotFound("tenant not found");
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> createTenant(Map<String, Object> body) {
        String name = name(body == null ? null : body.get("name"), true);
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM vod_tenant WHERE name = ?", Integer.class, name);
        if (dup != null && dup > 0) throw new UploadService.ConflictException("tenant name already exists");
        long id = insert("INSERT INTO vod_tenant(name, status) VALUES (?, 'ACTIVE')", name);
        applyLimits(id, body);
        return tenant(id);
    }

    /** 可改 name、status（ACTIVE/DISABLED）与三项配额；配额传 null 表示回到配置默认值。 */
    @Transactional
    public Map<String, Object> updateTenant(long id, Map<String, Object> body) {
        tenant(id);
        if (body == null) body = Map.of();
        if (body.containsKey("name")) {
            String name = name(body.get("name"), true);
            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM vod_tenant WHERE name = ? AND id <> ?", Integer.class, name, id);
            if (dup != null && dup > 0) throw new UploadService.ConflictException("tenant name already exists");
            jdbc.update("UPDATE vod_tenant SET name = ? WHERE id = ?", name, id);
        }
        if (body.containsKey("status")) {
            Object status = body.get("status");
            if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
                throw new IllegalArgumentException("status must be ACTIVE or DISABLED");
            }
            jdbc.update("UPDATE vod_tenant SET status = ? WHERE id = ?", status, id);
        }
        applyLimits(id, body);
        return tenant(id);
    }

    private void applyLimits(long id, Map<String, Object> body) {
        if (body == null) return;
        if (body.containsKey("storage_quota_bytes")) {
            jdbc.update("UPDATE vod_tenant SET storage_quota_bytes = ? WHERE id = ?",
                    nonNegativeLong(body.get("storage_quota_bytes"), "storage_quota_bytes"), id);
        }
        if (body.containsKey("max_concurrent_jobs")) {
            jdbc.update("UPDATE vod_tenant SET max_concurrent_jobs = ? WHERE id = ?",
                    nonNegativeInt(body.get("max_concurrent_jobs"), "max_concurrent_jobs"), id);
        }
        if (body.containsKey("max_daily_transcodes")) {
            jdbc.update("UPDATE vod_tenant SET max_daily_transcodes = ? WHERE id = ?",
                    nonNegativeInt(body.get("max_daily_transcodes"), "max_daily_transcodes"), id);
        }
    }

    public List<Map<String, Object>> keys(long tenantId) {
        tenant(tenantId);
        return jdbc.query("SELECT id, tenant_id, name, key_prefix, scopes, status, expires_at, created_at, revoked_at "
                + "FROM vod_api_credential WHERE tenant_id = ? ORDER BY id DESC", KEY_ROW, tenantId);
    }

    /** body：name、scopes（数组或逗号分隔，默认 upload,asset,job）、expires_at（ISO-8601）或 ttl_seconds。 */
    @Transactional
    public Map<String, Object> createKey(long tenantId, Map<String, Object> body) {
        tenant(tenantId);
        if (body == null) body = Map.of();
        String name = body.get("name") == null ? null : name(body.get("name"), false);
        String scopes = scopes(body.get("scopes"));
        Timestamp expiresAt = expiry(body.get("expires_at"), body.get("ttl_seconds"));
        return issue(tenantId, name, scopes, expiresAt);
    }

    @Transactional
    public Map<String, Object> revokeKey(long keyId) {
        key(keyId);
        jdbc.update("UPDATE vod_api_credential SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP "
                + "WHERE id = ? AND status = 'ACTIVE'", keyId);
        return key(keyId);
    }

    /**
     * 轮换：签一把同租户、同 scopes、同名、同过期时间的新 key。grace_seconds（默认 0）内旧 key 仍可用，之后过期；
     * 为 0 时旧 key 立即撤销。已撤销的 key 不能轮换（409）。
     */
    @Transactional
    public Map<String, Object> rotateKey(long keyId, Map<String, Object> body) {
        var rows = jdbc.query("SELECT tenant_id, name, scopes, status, expires_at FROM vod_api_credential WHERE id = ? FOR UPDATE",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5)},
                keyId);
        if (rows.isEmpty()) throw new NotFound("key not found");
        Object[] old = rows.get(0);
        if (!"ACTIVE".equals(old[3])) throw new UploadService.ConflictException("key is " + old[3] + ", cannot rotate");
        long grace = 0;
        if (body != null && body.get("grace_seconds") != null) {
            grace = nonNegativeLong(body.get("grace_seconds"), "grace_seconds");
        }
        if (grace > 0) {
            Timestamp until = Timestamp.from(Instant.now().plusSeconds(grace));
            Timestamp current = (Timestamp) old[4];
            if (current == null || current.after(until)) {
                jdbc.update("UPDATE vod_api_credential SET expires_at = ? WHERE id = ?", until, keyId);
            }
        } else {
            jdbc.update("UPDATE vod_api_credential SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP WHERE id = ?", keyId);
        }
        var out = issue((Long) old[0], (String) old[1], (String) old[2], (Timestamp) old[4]);
        out.put("rotated_from", keyId);
        out.put("previous_key", key(keyId));
        return out;
    }

    private Map<String, Object> key(long keyId) {
        var rows = jdbc.query("SELECT id, tenant_id, name, key_prefix, scopes, status, expires_at, created_at, revoked_at "
                + "FROM vod_api_credential WHERE id = ?", KEY_ROW, keyId);
        if (rows.isEmpty()) throw new NotFound("key not found");
        return rows.get(0);
    }

    private Map<String, Object> issue(long tenantId, String name, String scopes, Timestamp expiresAt) {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String plaintext = "vk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String prefix = plaintext.substring(0, 10);
        long id = insert("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes, status, expires_at, name, key_prefix) "
                + "VALUES (?,?,?, 'ACTIVE', ?,?,?)", tenantId, ApiKeys.sha256(plaintext), scopes, expiresAt, name, prefix);
        var out = new LinkedHashMap<String, Object>(key(id));
        // 明文只在这一次响应里出现。
        out.put("api_key", plaintext);
        return out;
    }

    private Timestamp expiry(Object expiresAt, Object ttlSeconds) {
        if (expiresAt != null && ttlSeconds != null) throw new IllegalArgumentException("give expires_at or ttl_seconds, not both");
        Instant at = null;
        if (expiresAt != null) {
            if (!(expiresAt instanceof String s)) throw new IllegalArgumentException("expires_at must be an ISO-8601 string");
            try {
                at = Instant.parse(s);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("expires_at must be an ISO-8601 instant");
            }
        } else if (ttlSeconds != null) {
            long ttl = nonNegativeLong(ttlSeconds, "ttl_seconds");
            if (ttl == 0) throw new IllegalArgumentException("ttl_seconds must be positive");
            at = Instant.now().plusSeconds(ttl);
        }
        if (at != null && !at.isAfter(Instant.now())) throw new IllegalArgumentException("expiry must be in the future");
        if (maxKeyTtlSeconds > 0) {
            Instant max = Instant.now().plusSeconds(maxKeyTtlSeconds);
            if (at == null || at.isAfter(max)) at = max;
        }
        return at == null ? null : Timestamp.from(at);
    }

    static String scopes(Object raw) {
        List<String> parts = new ArrayList<>();
        if (raw == null) {
            parts.addAll(List.of("upload", "asset", "job"));
        } else if (raw instanceof String s) {
            for (String p : s.split(",")) parts.add(p.trim());
        } else if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof String s)) throw new IllegalArgumentException("scopes must be strings");
                parts.add(s.trim());
            }
        } else {
            throw new IllegalArgumentException("scopes must be an array or a comma separated string");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!SCOPES.contains(p)) throw new IllegalArgumentException("unknown scope: " + p);
            out.add(p);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("at least one scope required");
        return String.join(",", out);
    }

    private static String name(Object raw, boolean required) {
        if (raw == null && !required) return null;
        if (!(raw instanceof String s) || !NAME.matcher(s.trim()).matches()) {
            throw new IllegalArgumentException("name must be 1-100 letters, digits, space, _ . -");
        }
        return s.trim();
    }

    private static Long nonNegativeLong(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof Integer || raw instanceof Long)) throw new IllegalArgumentException(field + " must be an integer or null");
        long v = ((Number) raw).longValue();
        if (v < 0) throw new IllegalArgumentException(field + " must be >= 0 (0 = unlimited)");
        return v;
    }

    private static Integer nonNegativeInt(Object raw, String field) {
        Long v = nonNegativeLong(raw, field);
        if (v == null) return null;
        if (v > Integer.MAX_VALUE) throw new IllegalArgumentException(field + " too large");
        return v.intValue();
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
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

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }
}
