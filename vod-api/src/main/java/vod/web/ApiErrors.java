package vod.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vod.auth.TenantPrincipal;
import vod.tenant.TenantGuard;
import vod.upload.UploadService;
import vod.usage.UsageService;
import vod.job.JobService;
import vod.media.PlaybackService;
import vod.admin.AdminAuthService;
import vod.admin.TenantAdminService;
import vod.quota.QuotaService;

import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(TenantGuard.ResourceNotVisibleException.class)
    ResponseEntity<Map<String, String>> hidden() {
        return ResponseEntity.status(404).body(Map.of("detail", "not found"));
    }

    @ExceptionHandler(UploadService.ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(UploadService.ConflictException e) {
        return ResponseEntity.status(409).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(UploadService.RejectedException.class)
    ResponseEntity<Map<String, String>> rejected(UploadService.RejectedException e) {
        return ResponseEntity.status(e.status()).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(UploadService.StorageUnavailableException.class)
    ResponseEntity<Map<String, String>> storageUnavailable(UploadService.StorageUnavailableException e) {
        return ResponseEntity.status(503).header("Retry-After", "5").body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(UsageService.QuotaExceededException.class)
    ResponseEntity<Map<String, String>> quota(UsageService.QuotaExceededException e) {
        return ResponseEntity.status(403).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(TenantPrincipal.ScopeDeniedException.class)
    ResponseEntity<Map<String, String>> scope(TenantPrincipal.ScopeDeniedException e) {
        return ResponseEntity.status(403).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(PlaybackService.NotReady.class)
    ResponseEntity<Map<String, String>> notReady() {
        return ResponseEntity.status(409).body(Map.of("detail", "asset not ready"));
    }

    /** 取消撞上死锁/锁超时，重试一次仍失败：503，让客户端稍后再试，而不是 500。 */
    @ExceptionHandler(JobService.BusyException.class)
    ResponseEntity<Map<String, String>> busy() {
        return ResponseEntity.status(503).header("Retry-After", "1").body(Map.of("detail", "busy, retry later"));
    }

    @ExceptionHandler(TenantAdminService.NotFound.class)
    ResponseEntity<Map<String, String>> notFound(TenantAdminService.NotFound e) {
        return ResponseEntity.status(404).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(AdminAuthService.BadCredentials.class)
    ResponseEntity<Map<String, String>> badCredentials() {
        return ResponseEntity.status(401).body(Map.of("detail", "invalid credentials"));
    }

    /** Phase 5 转码配额（并发、每日）：429 + code=quota_exceeded；每日上限带 Retry-After。限流也是 429，code=rate_limited。 */
    @ExceptionHandler(QuotaService.TranscodeQuotaException.class)
    ResponseEntity<Map<String, String>> transcodeQuota(QuotaService.TranscodeQuotaException e) {
        var builder = ResponseEntity.status(429);
        if (e.retryAfterSeconds() > 0) builder.header("Retry-After", Long.toString(e.retryAfterSeconds()));
        return builder.body(Map.of("detail", e.getMessage(), "code", "quota_exceeded", "quota", e.quota()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(Map.of("detail", e.getMessage()));
    }
}
