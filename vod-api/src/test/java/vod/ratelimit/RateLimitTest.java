package vod.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vod.auth.ApiKeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 5 第 7 项：租户 API 按租户限流、登录按 IP 限流，429 + Retry-After。用内存桶（测试不连 Redis）。 */
@SpringBootTest(properties = {"vod.ratelimit.tenant.rate-per-second=0.001", "vod.ratelimit.tenant.burst=3",
        "vod.ratelimit.login.rate-per-second=0.001", "vod.ratelimit.login.burst=2"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM vod_api_credential");
        jdbc.update("DELETE FROM vod_tenant");
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (11, 'A'), (12, 'B')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (11, ?, '*'), (11, ?, '*'), (12, ?, '*')",
                ApiKeys.sha256("rl-a1"), ApiKeys.sha256("rl-a2"), ApiKeys.sha256("rl-b"));
    }

    @Test
    void tenantBucketIsSharedAcrossKeysAndIsolatedFromOtherTenants() throws Exception {
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "rl-a1")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "rl-a2")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "rl-a1")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "rl-a2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "rl-b")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/healthz")).andExpect(status().isOk());
    }

    @Test
    void loginIsLimitedPerClientIp() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/admin/login").header("X-Real-IP", "203.0.113.9").contentType("application/json")
                    .content("{\"username\":\"x\",\"password\":\"y\"}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/admin/login").header("X-Real-IP", "203.0.113.9").contentType("application/json")
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(post("/api/v1/admin/login").header("X-Real-IP", "203.0.113.10").contentType("application/json")
                .content("{\"username\":\"x\",\"password\":\"y\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void bucketRefillsAndRedisFailureFallsBackToMemory() throws Exception {
        var limiter = new RateLimiter(new SimpleMeterRegistry(), true, "redis://127.0.0.1:1", 50, 1000, 1, 1, 1, 1, 1);
        assertTrue(limiter.tryAcquire("tenant", "1").allowed());
        var denied = limiter.tryAcquire("tenant", "1");
        assertFalse(denied.allowed());
        assertEquals(1, denied.retryAfterSeconds());
        Thread.sleep(1100);
        assertTrue(limiter.tryAcquire("tenant", "1").allowed());
        var off = new RateLimiter(new SimpleMeterRegistry(), false, "", 50, 0.001, 1, 1, 1, 1, 1);
        for (int i = 0; i < 5; i++) assertTrue(off.tryAcquire("tenant", "1").allowed());
        limiter.shutdown();
    }
}
