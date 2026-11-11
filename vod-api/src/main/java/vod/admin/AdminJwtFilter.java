package vod.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import vod.security.SecretGuard;

import java.io.IOException;
import java.util.List;

/**
 * 只挂在 /api/v1/admin/** 的安全过滤链里（见 {@link AdminSecurityConfig}），不是全局 Servlet 过滤器。
 * Authorization: Bearer vadm1.… 校验通过、且管理用户仍是 ACTIVE 时放入 ROLE_ADMIN 身份；否则什么都不放，由 Security 返回 401。
 * 租户 API Key 在这里永远验不过（没有 vadm1. 前缀、也不是这把密钥签的）。
 */
public class AdminJwtFilter extends OncePerRequestFilter {
    public static final String ATTR = "vod.admin";
    private final SecretGuard secrets;
    private final JdbcTemplate jdbc;
    private final long leewaySeconds;

    public AdminJwtFilter(SecretGuard secrets, JdbcTemplate jdbc, long leewaySeconds) {
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.leewaySeconds = leewaySeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            AdminJwt.Claims claims = AdminJwt.verify(auth.substring(7).trim(), secrets.adminJwtSecret(),
                    System.currentTimeMillis() / 1000, leewaySeconds);
            if (claims != null && active(claims)) {
                var authentication = new UsernamePasswordAuthenticationToken(claims.username(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute(ATTR, claims);
            }
        }
        chain.doFilter(request, response);
    }

    /** 停用或删除管理用户后，已签发的 token 立即失效（每个请求查一次，管理端流量小）。 */
    private boolean active(AdminJwt.Claims claims) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vod_admin_user WHERE id = ? AND username = ? AND status = 'ACTIVE'",
                Integer.class, claims.userId(), claims.username());
        return n != null && n > 0;
    }
}
