package vod.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vod.auth.ApiKeys;
import vod.webhook.FakeWebhookIo;
import vod.webhook.WebhookDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 的 SQL 在真实 MySQL 8.4 上跑一遍（整套 Spring 上下文连 MySQL，Flyway V1..V12）：租户行锁让并发重转只成功一个、
 * INSERT … SELECT 记事件、Idempotency-Key、投递领取、用量历史。默认跳过，设置 VOD_TEST_MYSQL_URL 后运行（见 MysqlSemanticsTest）。
 */
@EnabledIfEnvironmentVariable(named = "VOD_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase5MysqlTest {
    private static final String DB = "vod_p5_test";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry r) {
        var admin = new JdbcTemplate(MysqlSemanticsTest.ds("", ""));
        admin.execute("DROP DATABASE IF EXISTS " + DB);
        admin.execute("CREATE DATABASE " + DB + " CHARACTER SET utf8mb4");
        String base = System.getenv("VOD_TEST_MYSQL_URL").replaceAll("/+$", "");
        r.add("spring.datasource.url", () -> base + "/" + DB + "?allowPublicKeyRetrieval=true&useSSL=false");
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("VOD_TEST_MYSQL_USER", "root"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("VOD_TEST_MYSQL_PASSWORD", ""));
    }

    @org.junit.jupiter.api.AfterAll
    static void drop() {
        if (!"1".equals(System.getenv("VOD_TEST_MYSQL_KEEP"))) {
            new JdbcTemplate(MysqlSemanticsTest.ds("", "")).execute("DROP DATABASE IF EXISTS " + DB);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired FakeWebhookIo io;

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_idempotency_key", "vod_webhook_delivery", "vod_webhook_event", "vod_webhook_endpoint", "vod_outbox",
                "vod_transcode_job", "vod_media_asset", "vod_usage_daily", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name, max_concurrent_jobs) VALUES (1, 'A', 1)");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, '*')", ApiKeys.sha256("my-a"));
        jdbc.update("INSERT INTO vod_media_asset(id, tenant_id, status, source_bucket, source_key, source_size) VALUES "
                + "(5, 1, 'READY', 'b', 'k5', 10), (6, 1, 'FAILED', 'b', 'k6', 10)");
        jdbc.update("INSERT INTO vod_transcode_job(id, tenant_id, asset_id, profile, status, claim_version) VALUES "
                + "(9, 1, 5, 'ladder_v1', 'SUCCEEDED', 3), (10, 1, 6, 'ladder_v1', 'FAILED', 2)");
        io.reset();
    }

    @Test
    void concurrentRequestsRespectConcurrencyQuotaUnderTenantLock() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/assets/5/transcodes").header("X-Api-Key", "my-a")).andReturn().getResponse().getStatus();
            }));
            results.add(pool.submit(() -> {
                go.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/v1/jobs/10/retry").header("X-Api-Key", "my-a")).andReturn().getResponse().getStatus();
            }));
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (var f : results) codes.add(f.get(20, TimeUnit.SECONDS));
            codes.sort(Integer::compare);
            assertEquals(List.of(202, 429), codes, "max_concurrent_jobs=1: exactly one wins");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job WHERE status = 'PENDING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
    }

    @Test
    void retranscodeIdempotencyEventsDeliveryAndUsageOnMysql() throws Exception {
        jdbc.update("UPDATE vod_tenant SET max_concurrent_jobs = NULL");
        mvc.perform(post("/api/v1/assets/5/transcodes").header("X-Api-Key", "my-a").header("Idempotency-Key", "k1"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.claim_version").value(4));
        mvc.perform(post("/api/v1/assets/5/transcodes").header("X-Api-Key", "my-a").header("Idempotency-Key", "k1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotent_replay").value(true));
        mvc.perform(post("/api/v1/assets/5/transcodes").header("X-Api-Key", "my-a")).andExpect(status().isConflict());
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = 5", String.class));
        mvc.perform(post("/api/v1/jobs/9/cancel").header("X-Api-Key", "my-a")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/jobs/9/retry").header("X-Api-Key", "my-a")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.claim_version").value(6));
        mvc.perform(post("/api/v1/webhooks").header("X-Api-Key", "my-a").contentType("application/json")
                .content("{\"url\":\"https://hooks.example.com/x\",\"events\":\"*\"}")).andExpect(status().isOk());
        assertEquals(List.of("transcode.requested", "transcode.cancelled", "transcode.requested"),
                jdbc.queryForList("SELECT event_type FROM vod_webhook_event WHERE job_id = 9 ORDER BY id", String.class));
        dispatcher.runOnce();
        assertEquals(3, io.posts.size());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM vod_webhook_delivery WHERE status = 'DELIVERED'", Integer.class));
        assertEquals(0, dispatcher.runOnce());
        mvc.perform(get("/api/v1/usage?days=2").header("X-Api-Key", "my-a"))
                .andExpect(jsonPath("$.transcode_jobs").value(2))
                .andExpect(jsonPath("$.history[1].transcode_jobs").value(2))
                .andExpect(jsonPath("$.concurrency.active_jobs").value(1));
    }
}
