package vod.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vod.ratelimit.RateLimiter;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    public static final String ATTR = "vod.tenant";
    private final JdbcTemplate jdbc;
    private final RateLimiter limiter;

    public ApiKeyFilter(JdbcTemplate jdbc, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // /api/v1/qoe 用 body 里的 Core 播放 token 鉴权（sendBeacon 不能带头），见 vod.qoe.QoeController。
        // /api/v1/admin/** 由 Spring Security + 管理端 JWT 鉴权（vod.admin.AdminSecurityConfig），租户 Key 在那里无效。
        return !path.startsWith("/api/v1/") || path.equals("/api/v1/healthz") || path.equals("/api/v1/qoe")
                || path.startsWith("/api/v1/admin/") || path.equals("/api/v1/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = rawKey(request);
        if (raw == null || raw.isBlank()) {
            denied(response);
            return;
        }
        // expires_at 为 NULL 表示不过期；已过期、已撤销的 key 与未知 key 一样返回 401。
        // Phase 5：租户被停用后它的所有 key 一起失效（租户行不存在的旧数据按启用处理）。
        List<TenantPrincipal> rows = jdbc.query(
                "SELECT c.tenant_id, c.scopes FROM vod_api_credential c LEFT JOIN vod_tenant t ON t.id = c.tenant_id "
                        + "WHERE c.key_hash = ? AND c.status = 'ACTIVE' AND (c.expires_at IS NULL OR c.expires_at > ?) "
                        + "AND COALESCE(t.status, 'ACTIVE') = 'ACTIVE'",
                (rs, n) -> new TenantPrincipal(rs.getLong(1), rs.getString(2)),
                ApiKeys.sha256(raw), Timestamp.from(Instant.now()));
        if (rows.isEmpty()) {
            denied(response);
            return;
        }
        TenantPrincipal principal = rows.get(0);
        // Phase 5：按租户限流（不是按 key），同一租户多把 key 共享额度。
        RateLimiter.Decision decision = limiter.tryAcquire("tenant", Long.toString(principal.tenantId()));
        if (!decision.allowed()) {
            tooMany(response, decision.retryAfterSeconds());
            return;
        }
        request.setAttribute(ATTR, principal);
        chain.doFilter(request, response);
    }

    static String rawKey(HttpServletRequest request) {
        String header = request.getHeader("X-Api-Key");
        if (header != null && !header.isBlank()) return header.trim();
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7).trim();
        return null;
    }

    public static void tooMany(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        response.setContentType("application/json");
        response.getWriter().write("{\"detail\":\"rate limited\",\"code\":\"rate_limited\"}");
    }

    private static void denied(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"detail\":\"unauthorized\"}");
    }
}
