package vod.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 本地演示用。明文只来自环境变量，库里只存哈希。 */
@Component
@Profile("!test")
public class DevCredentialSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final String rawKey;

    public DevCredentialSeeder(JdbcTemplate jdbc, @Value("${vod.dev-api-key:}") String rawKey) {
        this.jdbc = jdbc;
        this.rawKey = rawKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (rawKey == null || rawKey.isBlank()) return;
        String hash = ApiKeys.sha256(rawKey);
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vod_api_credential WHERE key_hash = ?", Integer.class, hash);
        if (existing != null && existing > 0) return;
        Long tenantId = jdbc.query("SELECT id FROM vod_tenant WHERE name = 'drama-demo'",
                (rs, n) -> rs.getLong(1)).stream().findFirst().orElse(null);
        if (tenantId == null) {
            jdbc.update("INSERT INTO vod_tenant(name, status) VALUES ('drama-demo', 'ACTIVE')");
            tenantId = jdbc.queryForObject("SELECT id FROM vod_tenant WHERE name = 'drama-demo'", Long.class);
        }
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes, status) VALUES (?,?,?, 'ACTIVE')",
                tenantId, hash, "*");
    }
}
