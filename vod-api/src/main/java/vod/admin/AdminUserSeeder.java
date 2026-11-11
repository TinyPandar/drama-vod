package vod.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import vod.security.SecretGuard;

/**
 * 本地演示用：VOD_ADMIN_USERNAME + VOD_ADMIN_PASSWORD 都设置时，若该用户名还不存在就建一个管理用户（只存 BCrypt 哈希）。
 * 已存在时不覆盖口令。仓库和镜像里没有默认口令；非 dev 环境口令短于 vod.security.min-secret-length 时拒绝种子。
 */
@Component
@Profile("!test")
public class AdminUserSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SecretGuard secrets;
    private final String username;
    private final String password;
    private final int minLength;

    public AdminUserSeeder(JdbcTemplate jdbc, PasswordEncoder encoder, SecretGuard secrets,
                           @Value("${vod.admin.seed-username:}") String username,
                           @Value("${vod.admin.seed-password:}") String password,
                           @Value("${vod.security.min-secret-length:32}") int minLength) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.secrets = secrets;
        this.username = username;
        this.password = password;
        this.minLength = minLength;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) return;
        if (!secrets.dev() && password.length() < minLength) {
            log.warn("VOD_ADMIN_PASSWORD shorter than {} chars outside dev; admin user not seeded", minLength);
            return;
        }
        if (seed(jdbc, encoder, username.trim(), password)) log.info("seeded admin user from VOD_ADMIN_USERNAME");
    }

    static boolean seed(JdbcTemplate jdbc, PasswordEncoder encoder, String username, String password) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM vod_admin_user WHERE username = ?", Integer.class, username);
        if (existing != null && existing > 0) return false;
        try {
            jdbc.update("INSERT INTO vod_admin_user(username, password_hash, status) VALUES (?,?, 'ACTIVE')",
                    username, encoder.encode(password));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException race) {
            return false;  // 多实例同时启动
        }
    }
}
