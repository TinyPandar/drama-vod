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
import vod.upload.UploadService;

import java.util.Map;

@RestController
public class V1Controller {
    private final UploadService uploads;
    private final JobService jobService;
    private final int defaultPageSize;
    private final int maxPageSize;

    public V1Controller(UploadService uploads, JobService jobService,
                        @Value("${vod.api.page-size-default:20}") int defaultPageSize,
                        @Value("${vod.api.page-size-max:100}") int maxPageSize) {
        this.uploads = uploads;
        this.jobService = jobService;
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

    private static TenantPrincipal tenant(HttpServletRequest request, String scope) {
        return ((TenantPrincipal) request.getAttribute(ApiKeyFilter.ATTR)).require(scope);
    }
}
