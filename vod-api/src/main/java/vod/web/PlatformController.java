package vod.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vod.auth.ApiKeyFilter;
import vod.auth.TenantPrincipal;
import vod.usage.UsageService;
import vod.webhook.WebhookService;

import java.util.Map;

/** 租户侧 Webhook 与用量。Phase 2 起这两组接口不要求 scope（只要有效 key），Phase 5 保持不变。 */
@RestController
public class PlatformController {
    private final WebhookService webhooks;
    private final UsageService usage;
    private final int maxPageSize;

    public PlatformController(WebhookService webhooks, UsageService usage,
                              @Value("${vod.api.page-size-max:100}") int maxPageSize) {
        this.webhooks = webhooks;
        this.usage = usage;
        this.maxPageSize = maxPageSize;
    }

    /** body：url（HTTPS，过 SSRF 检查）、events（数组或逗号分隔，缺省 succeeded/failed/cancelled）。secret 只在这里返回一次。 */
    @PostMapping("/api/v1/webhooks")
    public Map<String, Object> register(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        Object url = body.get("url");
        if (!(url instanceof String u)) throw new IllegalArgumentException("url required");
        return webhooks.register(tenant(request).tenantId(), u, body.get("events"));
    }

    @GetMapping("/api/v1/webhooks")
    public Map<String, Object> list(HttpServletRequest request) {
        return Map.of("webhooks", webhooks.list(tenant(request).tenantId()));
    }

    @PostMapping("/api/v1/webhooks/{id}/disable")
    public Map<String, Object> disable(HttpServletRequest request, @PathVariable long id) {
        return webhooks.disable(tenant(request).tenantId(), id);
    }

    @PostMapping("/api/v1/webhooks/{id}/enable")
    public Map<String, Object> enable(HttpServletRequest request, @PathVariable long id) {
        return webhooks.enable(tenant(request).tenantId(), id);
    }

    /** 本租户的投递记录（最新在前），可按端点或任务过滤。不含签名密钥。 */
    @GetMapping("/api/v1/webhook-deliveries")
    public Map<String, Object> deliveries(HttpServletRequest request,
                                          @RequestParam(value = "endpoint_id", required = false) Long endpointId,
                                          @RequestParam(value = "job_id", required = false) Long jobId,
                                          @RequestParam(value = "limit", required = false) Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(1, limit), maxPageSize);
        return Map.of("deliveries", webhooks.deliveries(tenant(request).tenantId(), endpointId, jobId, size));
    }

    /** 今天的计数（原有顶层字段不变）+ 配额、已用存储、并发占用；?days=N 加最近 N 天历史（上限可配）。 */
    @GetMapping("/api/v1/usage")
    public Map<String, Object> usage(HttpServletRequest request,
                                     @RequestParam(value = "days", required = false) Integer days) {
        return usage.summary(tenant(request).tenantId(), days);
    }

    private static TenantPrincipal tenant(HttpServletRequest request) {
        return (TenantPrincipal) request.getAttribute(ApiKeyFilter.ATTR);
    }
}
