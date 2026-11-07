package vod.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    @GetMapping("/api/v1/healthz")
    public Map<String, Object> healthz() {
        return Map.of("ok", true, "service", "vod-api");
    }
}
