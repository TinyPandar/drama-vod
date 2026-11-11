package vod.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vod.auth.ApiKeyFilter;
import vod.ratelimit.RateLimiter;
import vod.template.TemplateService;
import vod.usage.UsageService;
import vod.webhook.WebhookService;

import java.io.IOException;
import java.util.Map;

/**
 * 管理端 API（Phase 5），全部在 /api/v1/admin/** 下，由 {@link AdminSecurityConfig} 要求管理端 JWT（登录除外）。
 * 这里拿到的身份不是租户身份：没有 {@link ApiKeyFilter#ATTR}，租户接口用它会 401。
 */
@RestController
public class AdminController {
    private final AdminAuthService auth;
    private final TenantAdminService tenants;
    private final TemplateService templates;
    private final UsageService usage;
    private final WebhookService webhooks;
    private final RateLimiter limiter;
    private final String clientIpHeader;
    private final int maxPageSize;

    public AdminController(AdminAuthService auth, TenantAdminService tenants, TemplateService templates, UsageService usage,
                           WebhookService webhooks, RateLimiter limiter,
                           @Value("${vod.ratelimit.client-ip-header:X-Real-IP}") String clientIpHeader,
                           @Value("${vod.api.page-size-max:100}") int maxPageSize) {
        this.auth = auth;
        this.tenants = tenants;
        this.templates = templates;
        this.usage = usage;
        this.webhooks = webhooks;
        this.limiter = limiter;
        this.clientIpHeader = clientIpHeader == null ? "" : clientIpHeader.trim();
        this.maxPageSize = maxPageSize;
    }

    /** 按客户端 IP 限流（vod.ratelimit.login.*），超限 429 + Retry-After。成功返回 token，no-store。 */
    @PostMapping("/api/v1/admin/login")
    public ResponseEntity<Map<String, Object>> login(HttpServletRequest request, HttpServletResponse response,
                                                     @RequestBody(required = false) Map<String, Object> body) throws IOException {
        RateLimiter.Decision decision = limiter.tryAcquire("login", clientIp(request));
        if (!decision.allowed()) {
            return ResponseEntity.status(429).header("Retry-After", Long.toString(decision.retryAfterSeconds()))
                    .body(Map.of("detail", "rate limited", "code", "rate_limited"));
        }
        Object username = body == null ? null : body.get("username");
        Object password = body == null ? null : body.get("password");
        if (!(username instanceof String u) || !(password instanceof String p)) throw new AdminAuthService.BadCredentials();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.login(u, p));
    }

    @GetMapping("/api/v1/admin/me")
    public Map<String, Object> me(HttpServletRequest request) {
        AdminJwt.Claims claims = (AdminJwt.Claims) request.getAttribute(AdminJwtFilter.ATTR);
        return Map.of("username", claims.username(), "expires_at", java.time.Instant.ofEpochSecond(claims.expiresAt()).toString());
    }

    @GetMapping("/api/v1/admin/tenants")
    public Map<String, Object> tenants() {
        return Map.of("tenants", tenants.tenants());
    }

    @PostMapping("/api/v1/admin/tenants")
    public ResponseEntity<Map<String, Object>> createTenant(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(tenants.createTenant(body));
    }

    @GetMapping("/api/v1/admin/tenants/{id}")
    public Map<String, Object> tenant(@PathVariable long id) {
        return tenants.tenant(id);
    }

    /** name、status（ACTIVE/DISABLED，停用后该租户所有 key 失效）、storage_quota_bytes、max_concurrent_jobs、max_daily_transcodes。 */
    @PatchMapping("/api/v1/admin/tenants/{id}")
    public Map<String, Object> updateTenant(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return tenants.updateTenant(id, body);
    }

    @GetMapping("/api/v1/admin/tenants/{id}/keys")
    public Map<String, Object> keys(@PathVariable long id) {
        return Map.of("keys", tenants.keys(id));
    }

    /** 明文 api_key 只在这一次响应里出现。 */
    @PostMapping("/api/v1/admin/tenants/{id}/keys")
    public ResponseEntity<Map<String, Object>> createKey(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(tenants.createKey(id, body));
    }

    @PostMapping("/api/v1/admin/keys/{keyId}/revoke")
    public Map<String, Object> revoke(@PathVariable long keyId) {
        return tenants.revokeKey(keyId);
    }

    /** body 可选 grace_seconds：旧 key 在宽限期内仍可用；0（默认）立即撤销。新明文只在这一次响应里出现。 */
    @PostMapping("/api/v1/admin/keys/{keyId}/rotate")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable long keyId, @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(tenants.rotateKey(keyId, body));
    }

    @GetMapping("/api/v1/admin/tenants/{id}/usage")
    public Map<String, Object> usage(@PathVariable long id, @RequestParam(value = "days", required = false) Integer days) {
        tenants.tenant(id);
        return usage.summary(id, days);
    }

    @GetMapping("/api/v1/admin/templates")
    public Map<String, Object> templates(@RequestParam(value = "tenant_id", required = false) Long tenantId) {
        return Map.of("templates", templates.all(tenantId));
    }

    @PostMapping("/api/v1/admin/tenants/{id}/templates")
    public ResponseEntity<Map<String, Object>> createTemplate(@PathVariable long id, @RequestBody Map<String, Object> body) {
        tenants.tenant(id);
        return ResponseEntity.status(201).body(templates.create(id, body));
    }

    /** 改租户模板；系统模板默认只读（vod.template.allow-system-edit）。 */
    @PutMapping("/api/v1/admin/templates/{templateId}")
    public Map<String, Object> updateTemplate(@PathVariable long templateId, @RequestBody Map<String, Object> body) {
        return templates.update(templateId, body);
    }

    /** 投递记录（可按租户、端点、任务过滤），不含签名密钥。 */
    @GetMapping("/api/v1/admin/webhook-deliveries")
    public Map<String, Object> deliveries(@RequestParam(value = "tenant_id", required = false) Long tenantId,
                                          @RequestParam(value = "endpoint_id", required = false) Long endpointId,
                                          @RequestParam(value = "job_id", required = false) Long jobId,
                                          @RequestParam(value = "limit", required = false) Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(1, limit), maxPageSize);
        return Map.of("deliveries", webhooks.deliveries(tenantId, endpointId, jobId, size));
    }

    /**
     * 登录限流按客户端 IP。vod-api 只在 Nginx 后面（compose 不暴露 8001），Nginx 写 X-Real-IP；
     * 直接暴露 vod-api 时应把 vod.ratelimit.client-ip-header 设成空串，只用 TCP 对端地址，否则客户端能伪造这个头。
     */
    private String clientIp(HttpServletRequest request) {
        if (!clientIpHeader.isEmpty()) {
            String v = request.getHeader(clientIpHeader);
            if (v != null && !v.isBlank() && v.length() <= 64) return v.trim();
        }
        return request.getRemoteAddr();
    }
}
