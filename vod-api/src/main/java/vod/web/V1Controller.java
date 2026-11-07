package vod.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import vod.auth.ApiKeyFilter;
import vod.auth.TenantPrincipal;
import vod.upload.UploadService;

import java.util.Map;

@RestController
public class V1Controller {
    private final UploadService uploads;

    public V1Controller(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/api/v1/uploads")
    public Map<String, Object> open(HttpServletRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody(required = false) Map<String, Object> body) {
        TenantPrincipal tenant = tenant(request);
        boolean encrypted = body != null && Boolean.TRUE.equals(body.get("encrypted"));
        return uploads.open(tenant.tenantId(), idempotencyKey, encrypted);
    }

    @PostMapping("/api/v1/uploads/{sessionId}/complete")
    public Map<String, Object> complete(HttpServletRequest request, @PathVariable long sessionId,
                                        @RequestHeader(value = "X-Request-ID", required = false) String traceId) {
        return uploads.complete(tenant(request).tenantId(), sessionId, traceId);
    }

    @GetMapping("/api/v1/assets/{assetId}")
    public Map<String, Object> asset(HttpServletRequest request, @PathVariable long assetId) {
        return uploads.asset(tenant(request).tenantId(), assetId);
    }

    @GetMapping("/api/v1/assets")
    public Map<String, Object> assets(HttpServletRequest request) {
        return Map.of("assets", uploads.assets(tenant(request).tenantId()));
    }

    @GetMapping("/api/v1/jobs")
    public Map<String, Object> jobs(HttpServletRequest request) {
        return Map.of("jobs", uploads.jobs(tenant(request).tenantId()));
    }

    private static TenantPrincipal tenant(HttpServletRequest request) {
        return (TenantPrincipal) request.getAttribute(ApiKeyFilter.ATTR);
    }
}
