package vod.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vod.auth.ApiKeyFilter;
import vod.auth.TenantPrincipal;
import vod.media.HlsSigner;
import vod.tenant.TenantGuard;
import vod.usage.UsageService;

import java.time.Instant;
import java.util.Map;

@RestController
public class PlaybackController {
    private final JdbcTemplate jdbc;
    private final UsageService usage;
    private final String hlsSecret;
    private final long ttlSeconds;

    public PlaybackController(JdbcTemplate jdbc, UsageService usage,
                              @Value("${vod.hls-secret:dev}") String hlsSecret,
                              @Value("${vod.play-ttl-seconds:600}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.usage = usage;
        this.hlsSecret = hlsSecret;
        this.ttlSeconds = ttlSeconds;
    }

    @GetMapping("/api/v1/assets/{assetId}/playback")
    public Map<String, Object> playback(HttpServletRequest request, @PathVariable long assetId) {
        TenantPrincipal tenant = ((TenantPrincipal) request.getAttribute(ApiKeyFilter.ATTR)).require("asset");
        var rows = jdbc.query(
                "SELECT tenant_id, status FROM vod_media_asset WHERE id = ?",
                (rs, n) -> Map.of("tenant_id", rs.getLong(1), "status", rs.getString(2)),
                assetId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        TenantGuard.requireVisible(tenant.tenantId(), (Long) rows.get(0).get("tenant_id"));
        if (!"READY".equals(rows.get(0).get("status"))) throw new NotReady();
        usage.addPlay(tenant.tenantId());
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        return Map.of(
                "asset_id", assetId,
                "master_url", HlsSigner.v1MasterUrl(exp, assetId, hlsSecret),
                "expires_at", exp);
    }

    public static class NotReady extends RuntimeException {}
}
