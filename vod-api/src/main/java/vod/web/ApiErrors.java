package vod.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vod.tenant.TenantGuard;
import vod.upload.UploadService;
import vod.web.PlaybackController;

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

    @ExceptionHandler(PlaybackController.NotReady.class)
    ResponseEntity<Map<String, String>> notReady() {
        return ResponseEntity.status(409).body(Map.of("detail", "asset not ready"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(400).body(Map.of("detail", e.getMessage()));
    }
}
