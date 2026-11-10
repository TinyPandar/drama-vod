package vod.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    public static final String ATTR = "vod.tenant";
    private final JdbcTemplate jdbc;

    public ApiKeyFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // /api/v1/qoe 用 body 里的 Core 播放 token 鉴权（sendBeacon 不能带头），见 vod.qoe.QoeController。
        return !path.startsWith("/api/v1/") || path.equals("/api/v1/healthz") || path.equals("/api/v1/qoe");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = rawKey(request);
        if (raw == null || raw.isBlank()) {
            denied(response);
            return;
        }
        // expires_at 为 NULL 表示不过期；已过期的 key 与未知 key 一样返回 401。
        List<TenantPrincipal> rows = jdbc.query(
                "SELECT tenant_id, scopes FROM vod_api_credential WHERE key_hash = ? AND status = 'ACTIVE' "
                        + "AND (expires_at IS NULL OR expires_at > ?)",
                (rs, n) -> new TenantPrincipal(rs.getLong(1), rs.getString(2)),
                ApiKeys.sha256(raw), Timestamp.from(Instant.now()));
        if (rows.isEmpty()) {
            denied(response);
            return;
        }
        request.setAttribute(ATTR, rows.get(0));
        chain.doFilter(request, response);
    }

    static String rawKey(HttpServletRequest request) {
        String header = request.getHeader("X-Api-Key");
        if (header != null && !header.isBlank()) return header.trim();
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7).trim();
        return null;
    }

    private static void denied(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"detail\":\"unauthorized\"}");
    }
}
