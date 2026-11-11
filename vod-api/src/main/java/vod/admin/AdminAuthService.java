package vod.admin;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vod.security.SecretGuard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理登录：用户名 + 口令换 vadm1. token。失败一律 401 同一句话，不区分用户不存在和口令错。 */
@Service
public class AdminAuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SecretGuard secrets;
    private final long ttlSeconds;
    private final MeterRegistry registry;
    // 用户不存在时也做一次 BCrypt 比对，让两种失败耗时接近。启动时随机生成，不是口令。
    private final String dummyHash;

    public AdminAuthService(JdbcTemplate jdbc, PasswordEncoder encoder, SecretGuard secrets, MeterRegistry registry,
                            @Value("${vod.admin.jwt-ttl-seconds:3600}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.secrets = secrets;
        this.registry = registry;
        this.ttlSeconds = Math.max(60, ttlSeconds);
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null || username.isBlank() || username.length() > 64 || password.length() > 200) {
            fail();
        }
        List<Object[]> rows = jdbc.query("SELECT id, password_hash FROM vod_admin_user WHERE username = ? AND status = 'ACTIVE'",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, username.trim());
        if (rows.isEmpty()) {
            encoder.matches(password, dummyHash);
            fail();
        }
        long id = (Long) rows.get(0)[0];
        if (!encoder.matches(password, (String) rows.get(0)[1])) fail();
        long now = Instant.now().getEpochSecond();
        long exp = now + ttlSeconds;
        String token = AdminJwt.issue(new AdminJwt.Claims(id, username.trim(), now, exp), secrets.adminJwtSecret());
        jdbc.update("UPDATE vod_admin_user SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        registry.counter("vod.admin.logins", "result", "ok").increment();
        var out = new LinkedHashMap<String, Object>();
        out.put("token", token);
        out.put("token_type", "Bearer");
        out.put("expires_at", Instant.ofEpochSecond(exp).toString());
        out.put("expires_in", ttlSeconds);
        out.put("username", username.trim());
        return out;
    }

    private void fail() {
        registry.counter("vod.admin.logins", "result", "denied").increment();
        throw new BadCredentials();
    }

    public static class BadCredentials extends RuntimeException {
        public BadCredentials() {
            super("invalid credentials");
        }
    }
}
