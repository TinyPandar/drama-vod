package vod.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vod.auth.ApiKeyFilter;
import vod.auth.TenantPrincipal;
import vod.job.JobService;
import vod.job.TranscodeService;
import vod.template.TemplateService;
import vod.upload.UploadService;

import java.util.Map;

@RestController
public class V1Controller {
    private final UploadService uploads;
    private final JobService jobService;
    private final TranscodeService transcodes;
    private final TemplateService templates;
    private final int defaultPageSize;
    private final int maxPageSize;

    public V1Controller(UploadService uploads, JobService jobService, TranscodeService transcodes, TemplateService templates,
                        @Value("${vod.api.page-size-default:20}") int defaultPageSize,
                        @Value("${vod.api.page-size-max:100}") int maxPageSize) {
        this.uploads = uploads;
        this.jobService = jobService;
        this.transcodes = transcodes;
        this.templates = templates;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
    }

    @PostMapping("/api/v1/uploads")
    public Map<String, Object> open(HttpServletRequest request,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody(required = false) Map<String, Object> body) {
        TenantPrincipal tenant = tenant(request, "upload");
        boolean encrypted = body != null && Boolean.TRUE.equals(body.get("encrypted"));
        String filename = body != null && body.get("filename") instanceof String name ? name : null;
        return uploads.open(tenant.tenantId(), idempotencyKey, encrypted, filename);
    }

    @PostMapping("/api/v1/uploads/{sessionId}/complete")
    public Map<String, Object> complete(HttpServletRequest request, @PathVariable long sessionId,
                                        @RequestHeader(value = "X-Request-ID", required = false) String traceId) {
        return uploads.complete(tenant(request, "upload").tenantId(), sessionId, traceId);
    }

    @GetMapping("/api/v1/assets/{assetId}")
    public Map<String, Object> asset(HttpServletRequest request, @PathVariable long assetId) {
        return uploads.asset(tenant(request, "asset").tenantId(), assetId);
    }

    /** 游标分页：limit 默认与上限来自配置，cursor 传上一页返回的 next_cursor。 */
    @GetMapping("/api/v1/assets")
    public Map<String, Object> assets(HttpServletRequest request,
                                      @RequestParam(value = "limit", required = false) Integer limit,
                                      @RequestParam(value = "cursor", required = false) Long cursor) {
        long tenantId = tenant(request, "asset").tenantId();
        if (limit != null && limit <= 0) throw new IllegalArgumentException("limit must be positive");
        if (cursor != null && cursor <= 0) throw new IllegalArgumentException("cursor must be positive");
        int size = Math.min(limit == null ? defaultPageSize : limit, maxPageSize);
        return uploads.assets(tenantId, size, cursor);
    }

    @GetMapping("/api/v1/jobs")
    public Map<String, Object> jobs(HttpServletRequest request) {
        return Map.of("jobs", uploads.jobs(tenant(request, "job").tenantId()));
    }

    @GetMapping("/api/v1/jobs/{jobId}")
    public Map<String, Object> job(HttpServletRequest request, @PathVariable long jobId) {
        return uploads.job(tenant(request, "job").tenantId(), jobId);
    }

    /** 取消 Core 任务：200 返回取消后的任务详情（已取消重复调用同样 200），终态 409；锁冲突重试一次仍失败 503。 */
    @PostMapping("/api/v1/jobs/{jobId}/cancel")
    public Map<String, Object> cancel(HttpServletRequest request, @PathVariable long jobId) {
        long tenantId = tenant(request, "job").tenantId();
        JobService.cancelWithRetry(() -> jobService.cancel(tenantId, jobId));
        return uploads.job(tenantId, jobId);
    }

    /**
     * Phase 5：按模板发起转码 / 重新转码。body 可选 {"template": "模板名"}，缺省用默认模板 ladder_v1。scope asset。
     * 202 + 任务详情；同一资产有任一任务（任意模板）还在 PENDING/RETRYING/RUNNING 时 409；终态任务复用同一行，claim_version+1。
     * 支持 Idempotency-Key（重放返回 200 + 同一任务，idempotent_replay=true）。
     */
    @PostMapping("/api/v1/assets/{assetId}/transcodes")
    public org.springframework.http.ResponseEntity<Map<String, Object>> transcode(
            HttpServletRequest request, @PathVariable long assetId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) String traceId,
            @RequestBody(required = false) Map<String, Object> body) {
        long tenantId = tenant(request, "asset").tenantId();
        Object template = body == null ? null : body.get("template");
        if (template != null && !(template instanceof String)) throw new IllegalArgumentException("template must be a string");
        var result = transcodes.transcode(tenantId, assetId, (String) template, idempotencyKey, traceId);
        return accepted(tenantId, result);
    }

    /** Phase 5：重试本租户 FAILED（配置允许时含 CANCELLED）的任务。scope job。跨租户 404，其他状态 409。支持 Idempotency-Key。 */
    @PostMapping("/api/v1/jobs/{jobId}/retry")
    public org.springframework.http.ResponseEntity<Map<String, Object>> retry(
            HttpServletRequest request, @PathVariable long jobId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) String traceId) {
        long tenantId = tenant(request, "job").tenantId();
        return accepted(tenantId, transcodes.retry(tenantId, jobId, idempotencyKey, traceId));
    }

    /** Phase 5：当前租户可用的转码模板（系统模板 + 本租户模板）。scope asset。 */
    @GetMapping("/api/v1/templates")
    public Map<String, Object> templates(HttpServletRequest request) {
        return Map.of("templates", templates.visible(tenant(request, "asset").tenantId()));
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> accepted(long tenantId, TranscodeService.Result result) {
        var out = new java.util.LinkedHashMap<String, Object>(uploads.job(tenantId, result.jobId()));
        out.put("idempotent_replay", result.replayed());
        return org.springframework.http.ResponseEntity.status(result.replayed() ? 200 : 202).body(out);
    }

    private static TenantPrincipal tenant(HttpServletRequest request, String scope) {
        return ((TenantPrincipal) request.getAttribute(ApiKeyFilter.ATTR)).require(scope);
    }
}
