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
    void concurrentRetranscodesWithDifferentTemplatesOnSameAssetOnlyOneWins() throws Exception {
        jdbc.update("UPDATE vod_tenant SET max_concurrent_jobs = NULL");
        jdbc.update("DELETE FROM vod_transcode_template WHERE tenant_id = 1");
        jdbc.update("INSERT INTO vod_transcode_template(tenant_id, name, ladder_json, audio_kbps) VALUES (1, 'mobile', '[[\"360p\",360,500]]', 64)");
        var pool = Executors.newFixedThreadPool(2);
        var go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String body : List.of("{\"template\":\"ladder_v1\"}", "{\"template\":\"mobile\"}")) {
                results.add(pool.submit(() -> {
                    go.await(5, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/v1/assets/5/transcodes").header("X-Api-Key", "my-a")
                            .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (var f : results) codes.add(f.get(20, TimeUnit.SECONDS));
            codes.sort(Integer::compare);
            assertEquals(List.of(202, 409), codes, "one in-flight transcode per asset, whatever the template");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job WHERE asset_id = 5 AND status = 'PENDING'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
        // 进行中的那个结束前，另一个模板仍然 409
        String pending = jdbc.queryForObject("SELECT profile FROM vod_transcode_job WHERE asset_id = 5 AND status = 'PENDING'", String.class);
        String other = "ladder_v1".equals(pending) ? "mobile" : "ladder_v1";
        mvc.perform(post("/api/v1/assets/5/transcodes").header("X-Api-Key", "my-a").contentType("application/json")
                .content("{\"template\":\"" + other + "\"}")).andExpect(status().isConflict());
    }

    @Test
    void concurrentRetriesAndRetranscodeOnSameAssetLeaveOnlyOneActiveJob() throws Exception {
        jdbc.update("UPDATE vod_tenant SET max_concurrent_jobs = NULL");
        jdbc.update("DELETE FROM vod_transcode_template WHERE tenant_id = 1");
        jdbc.update("INSERT INTO vod_transcode_template(tenant_id, name, ladder_json, audio_kbps) VALUES "
                + "(1, 'mobile', '[[\"360p\",360,500]]', 64), (1, 'hd', '[[\"720p\",720,2000]]', 128)");
        jdbc.update("INSERT INTO vod_transcode_job(id, tenant_id, asset_id, profile, status, claim_version) VALUES "
                + "(11, 1, 6, 'mobile', 'FAILED', 1)");
        // 顺序：mobile 进行中时重试 ladder_v1 → 409，不改行、不写 outbox
        jdbc.update("UPDATE vod_transcode_job SET status = 'RUNNING' WHERE id = 11");
        mvc.perform(post("/api/v1/jobs/10/retry").header("X-Api-Key", "my-a").header("Idempotency-Key", "r-x"))
                .andExpect(status().isConflict());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE id = 10", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT claim_version FROM vod_transcode_job WHERE id = 10", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_idempotency_key", Integer.class));
        // 并发：两条重试（不同模板）+ 一条第三个模板的重新转码同时打同一个资产，多轮都只能有一个进行中
        for (int round = 0; round < 10; round++) {
            jdbc.update("DELETE FROM vod_outbox");
            jdbc.update("DELETE FROM vod_transcode_job WHERE asset_id = 6 AND profile = 'hd'");
            jdbc.update("UPDATE vod_transcode_job SET status = 'FAILED' WHERE asset_id = 6");
            var pool = Executors.newFixedThreadPool(3);
            var go = new CountDownLatch(1);
            List<Integer> codes = new ArrayList<>();
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (String path : List.of("/api/v1/jobs/10/retry", "/api/v1/jobs/11/retry")) {
                    results.add(pool.submit(() -> {
                        go.await(5, TimeUnit.SECONDS);
                        return mvc.perform(post(path).header("X-Api-Key", "my-a")).andReturn().getResponse().getStatus();
                    }));
                }
                results.add(pool.submit(() -> {
                    go.await(5, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/v1/assets/6/transcodes").header("X-Api-Key", "my-a")
                            .contentType("application/json").content("{\"template\":\"hd\"}")).andReturn().getResponse().getStatus();
                }));
                go.countDown();
                for (var f : results) codes.add(f.get(20, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            codes.sort(Integer::compare);
            assertEquals(List.of(202, 409, 409), codes, "round " + round + ": one in-flight job per asset");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job WHERE asset_id = 6 "
                    + "AND status IN ('PENDING','RETRYING','RUNNING')", Integer.class), "round " + round);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class), "round " + round);
        }
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
