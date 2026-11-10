package vod.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vod.auth.ApiKeyFilter;
import vod.auth.TenantPrincipal;
import vod.media.PlaybackService;

import java.util.Map;

@RestController
public class PlaybackController {
    private final PlaybackService playback;

    public PlaybackController(PlaybackService playback) {
        this.playback = playback;
    }

    @GetMapping("/api/v1/assets/{assetId}/playback")
    public Map<String, Object> playback(HttpServletRequest request, @PathVariable long assetId) {
        return playback.playback(tenant(request).tenantId(), assetId);
    }

    /** 签发 Core 播放 token。可选 body：ttl_seconds（上限走配置）、viewer_id（不透明，只用于日志/QoE）。 */
    @PostMapping("/api/v1/assets/{assetId}/playback-token")
    public ResponseEntity<Map<String, Object>> playbackToken(HttpServletRequest request, @PathVariable long assetId,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        long tenantId = tenant(request).tenantId();
        Long ttl = null;
        String viewer = null;
        if (body != null) {
            Object rawTtl = body.get("ttl_seconds");
            if (rawTtl != null) {
                if (!(rawTtl instanceof Integer || rawTtl instanceof Long)) {
                    throw new IllegalArgumentException("ttl_seconds must be an integer");
                }
                ttl = ((Number) rawTtl).longValue();
            }
            Object rawViewer = body.get("viewer_id");
            if (rawViewer != null) {
                if (!(rawViewer instanceof String s)) throw new IllegalArgumentException("viewer_id must be a string");
                viewer = s;
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(playback.issueToken(tenantId, assetId, ttl, viewer));
    }

    private static TenantPrincipal tenant(HttpServletRequest request) {
        return ((TenantPrincipal) request.getAttribute(ApiKeyFilter.ATTR)).require("asset");
    }
}
