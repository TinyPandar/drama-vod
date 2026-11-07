package vod.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vod.auth.ApiKeyFilter;
import vod.auth.TenantPrincipal;
import vod.usage.UsageService;
import vod.webhook.WebhookService;

import java.util.Map;

@RestController
public class PlatformController {
    private final WebhookService webhooks;
    private final UsageService usage;

    public PlatformController(WebhookService webhooks, UsageService usage) {
        this.webhooks = webhooks;
        this.usage = usage;
    }

    @PostMapping("/api/v1/webhooks")
    public Map<String, Object> register(HttpServletRequest request, @RequestBody Map<String, String> body) {
        return webhooks.register(tenant(request).tenantId(), body.get("url"), body.get("events"));
    }

    @GetMapping("/api/v1/webhooks")
    public Map<String, Object> list(HttpServletRequest request) {
        return Map.of("webhooks", webhooks.list(tenant(request).tenantId()));
    }

    @GetMapping("/api/v1/usage")
    public Map<String, Object> usage(HttpServletRequest request) {
        return usage.today(tenant(request).tenantId());
    }

    private static TenantPrincipal tenant(HttpServletRequest request) {
        return (TenantPrincipal) request.getAttribute(ApiKeyFilter.ATTR);
    }
}
