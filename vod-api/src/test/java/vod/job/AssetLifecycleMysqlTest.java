package vod.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import vod.asset.RetentionCleanup;
import vod.asset.StorageObjectCleanup;
import vod.auth.ApiKeys;
import vod.storage.MemoryStorage;
import vod.upload.UploadCleanup;

import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路线 B 的 SQL 在真实 MySQL 8.4 上跑（Flyway V1..V14）：删除与并发重转只有一个生效且不死锁；
 * 保留期清理的 DELETE … LIMIT（含 NOT EXISTS 子查询）；上传收尾与源对象清理的 TIMESTAMP 比较；标题 LIKE 转义。
 * 默认跳过，设置 VOD_TEST_MYSQL_URL 后运行（见 MysqlSemanticsTest）。
 */
@EnabledIfEnvironmentVariable(named = "VOD_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(properties = {"vod.upload.max-object-bytes=104857600", "vod.upload.put-abandon-grace-seconds=0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetLifecycleMysqlTest {
    private static final String DB = "vod_b_test";

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
    @Autowired MemoryStorage storage;
    @Autowired UploadCleanup uploadCleanup;
    @Autowired StorageObjectCleanup objectCleanup;
    @Autowired RetentionCleanup retention;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_asset_tag", "vod_idempotency_key", "vod_output_prefix", "vod_webhook_delivery",
                "vod_webhook_endpoint", "vod_webhook_event", "vod_outbox", "vod_transcode_job", "vod_upload_session",
                "vod_media_asset", "vod_storage_object", "vod_usage_daily", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, '*')", ApiKeys.sha256("my-a"));
        storage.clear();
    }

    @Test
    void deleteRacingRetranscodeHasExactlyOneWinnerOnMysql() throws Exception {
        for (int round = 0; round < 10; round++) {
            long asset = readyAsset();
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            var del = pool.submit(() -> {
                start.await();
                return mvc.perform(delete("/api/v1/assets/" + asset).header("X-Api-Key", "my-a")).andReturn().getResponse().getStatus();
            });
            var tc = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/assets/" + asset + "/transcodes").header("X-Api-Key", "my-a"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int d = del.get(30, TimeUnit.SECONDS);
            int t = tc.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            String assetStatus = jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, asset);
            String jobStatus = jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE asset_id = ?", String.class, asset);
            if (d == 200) {
                // 删除先拿到锁：重转看到 DELETED → 404，任务保持终态
                assertEquals(404, t, "round " + round);
                assertEquals("DELETED", assetStatus);
                assertEquals("SUCCEEDED", jobStatus);
            } else {
                // 重转先拿到锁：删除看到进行中的任务 → 409
                assertEquals(409, d, "round " + round);
                assertEquals(202, t);
                assertEquals("READY", assetStatus);
                assertEquals("PENDING", jobStatus);
            }
        }
    }

    @Test
    void retentionDeleteWithLimitWorksOnMysql() {
        Timestamp old = new Timestamp(System.currentTimeMillis() - 40L * 86_400_000L);
        jdbc.update("INSERT INTO vod_webhook_endpoint(id, tenant_id, url, secret, events) VALUES (1, 1, 'https://e.example/h', 's', '*')");
        for (int i = 1; i <= 7; i++) {
            jdbc.update("INSERT INTO vod_webhook_event(id, tenant_id, event_type, status, created_at) VALUES (?, 1, 'transcode.succeeded', 'DISPATCHED', ?)",
                    i, old);
            jdbc.update("INSERT INTO vod_webhook_delivery(tenant_id, endpoint_id, event_type, status, event_id, created_at) "
                    + "VALUES (1, 1, 'transcode.succeeded', ?, ?, ?)", i == 7 ? "PENDING" : "DELIVERED", i, old);
            jdbc.update("INSERT INTO vod_outbox(tenant_id, event_type, payload, status, published_at) VALUES (1, 'transcode.requested', '{}', 'PUBLISHED', ?)", old);
        }
        var removed = retention.runOnce();
        assertEquals(6, removed.get("webhook_delivery"));
        assertEquals(6, removed.get("webhook_event"));
        assertEquals(7, removed.get("outbox"));
        assertEquals(List.of(7L), jdbc.queryForList("SELECT id FROM vod_webhook_event", Long.class));
    }

    @Test
    void uploadAndObjectCleanupCompareTimestampsOnMysql() throws Exception {
        JsonNode opened = json.readTree(mvc.perform(post("/api/v1/uploads").header("X-Api-Key", "my-a")
                .contentType("application/json").content("{\"title\":\"50% off_sale\"}")).andReturn().getResponse().getContentAsString());
        long session = opened.get("session_id").asLong();
        long asset = opened.get("asset_id").asLong();
        String key = jdbc.queryForObject("SELECT object_key FROM vod_upload_session WHERE id = ?", String.class, session);
        storage.put("vod-source", key);

        var page = json.readTree(mvc.perform(get("/api/v1/assets").param("q", "50% off_").header("X-Api-Key", "my-a"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(1, page.get("assets").size());
        page = json.readTree(mvc.perform(get("/api/v1/assets").param("q", "5_%").header("X-Api-Key", "my-a"))
                .andReturn().getResponse().getContentAsString());
        assertEquals(0, page.get("assets").size(), "_ 和 % 按字面匹配");

        assertEquals(0, uploadCleanup.runOnce(), "还没过期");
        jdbc.update("UPDATE vod_upload_session SET expires_at = ? WHERE id = ?", new Timestamp(System.currentTimeMillis() - 2000), session);
        assertEquals(1, uploadCleanup.runOnce());
        assertEquals("DELETED", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, asset));
        assertEquals(1, objectCleanup.runOnce());
        assertTrue(storage.head("vod-source", key).isEmpty());
    }

    private long readyAsset() throws Exception {
        JsonNode opened = json.readTree(mvc.perform(post("/api/v1/uploads").header("X-Api-Key", "my-a"))
                .andReturn().getResponse().getContentAsString());
        long session = opened.get("session_id").asLong();
        long asset = opened.get("asset_id").asLong();
        storage.put("vod-source", jdbc.queryForObject("SELECT object_key FROM vod_upload_session WHERE id = ?", String.class, session));
        JsonNode done = json.readTree(mvc.perform(post("/api/v1/uploads/" + session + "/complete").header("X-Api-Key", "my-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long job = done.get("job_id").asLong();
        String prefix = "assets/v1/" + asset + "/" + job + "-1/";
        jdbc.update("UPDATE vod_transcode_job SET status = 'SUCCEEDED', claim_version = 1 WHERE id = ?", job);
        jdbc.update("UPDATE vod_media_asset SET status = 'READY', hls_prefix = ? WHERE id = ?", prefix, asset);
        jdbc.update("INSERT INTO vod_output_prefix(asset_id, job_id, claim_version, prefix, status) VALUES (?, ?, 1, ?, 'ACTIVE')",
                asset, job, prefix);
        return asset;
    }
}
