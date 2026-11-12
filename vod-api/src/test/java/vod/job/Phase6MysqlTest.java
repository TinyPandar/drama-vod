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
import vod.auth.ApiKeys;
import vod.storage.MemoryStorage;
import vod.upload.UploadCleanup;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 的 SQL 在真实 MySQL 8.4 上跑（Flyway V1..V13）：multipart 会话、会话行 FOR UPDATE 让并发 complete / abort
 * 只有一个生效、过期清理的 TIMESTAMP 比较与条件领取。默认跳过，设置 VOD_TEST_MYSQL_URL 后运行（见 MysqlSemanticsTest）。
 */
@EnabledIfEnvironmentVariable(named = "VOD_TEST_MYSQL_URL", matches = ".+")
@SpringBootTest(properties = "vod.upload.max-object-bytes=104857600")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase6MysqlTest {
    private static final String DB = "vod_p6_test";

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
    @Autowired UploadCleanup cleanup;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_webhook_event", "vod_outbox", "vod_transcode_job", "vod_upload_session", "vod_media_asset",
                "vod_storage_object", "vod_usage_daily", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, '*')", ApiKeys.sha256("my-a"));
        storage.clear();
    }

    @Test
    void multipartRoundTripOnMysql() throws Exception {
        JsonNode opened = openMultipart("mp-mysql");
        long sessionId = opened.get("session_id").asLong();
        assertEquals(opened.get("session_id").asLong(), openMultipart("mp-mysql").get("session_id").asLong());
        assertEquals(1, storage.openUploads());
        String uploadId = uploadId(sessionId);
        String e1 = storage.uploadPart(uploadId, 1, 5L * 1024 * 1024);
        String e2 = storage.uploadPart(uploadId, 2, 10);
        complete(sessionId, "{\"parts\":[{\"part_number\":1,\"etag\":\"" + e1 + "\"},{\"part_number\":2,\"etag\":\"bad\"}]}")
                .andExpect(status().isBadRequest());
        assertEquals(0, count("vod_transcode_job"));
        complete(sessionId, "{\"parts\":[{\"part_number\":1,\"etag\":\"" + e1 + "\"},{\"part_number\":2,\"etag\":\"" + e2 + "\"}]}")
                .andExpect(status().isOk());
        assertEquals(1, count("vod_transcode_job"));
        assertEquals("COMPLETED", sessionStatus(sessionId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_upload_session WHERE updated_at IS NOT NULL", Integer.class));
    }

    @Test
    void concurrentCompleteAndAbortNeverBothSucceed() throws Exception {
        for (int round = 0; round < 10; round++) {
            long sessionId = openMultipart(null).get("session_id").asLong();
            String e1 = storage.uploadPart(uploadId(sessionId), 1, 100);
            String body = "{\"parts\":[{\"part_number\":1,\"etag\":\"" + e1 + "\"}]}";
            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            var c = pool.submit(() -> {
                start.await();
                return complete(sessionId, body).andReturn().getResponse().getStatus();
            });
            var a = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/uploads/" + sessionId + "/abort").header("X-Api-Key", "my-a"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int cs = c.get(30, TimeUnit.SECONDS), as = a.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            String st = sessionStatus(sessionId);
            int jobs = jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job j JOIN vod_upload_session s "
                    + "ON s.asset_id = j.asset_id WHERE s.id = ?", Integer.class, sessionId);
            if ("COMPLETED".equals(st)) {
                assertEquals(200, cs, "round " + round);
                assertEquals(409, as, "round " + round);
                assertEquals(1, jobs);
            } else {
                assertEquals("ABORTED", st, "round " + round);
                assertEquals(200, as, "round " + round);
                assertTrue(cs == 409, "round " + round + " complete=" + cs);
                assertEquals(0, jobs);
            }
        }
    }

    @Test
    void cleanupOnMysqlClaimsExpiredOnly() throws Exception {
        long expired = openMultipart(null).get("session_id").asLong();
        long fresh = openMultipart(null).get("session_id").asLong();
        jdbc.update("UPDATE vod_upload_session SET expires_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(5)), expired);
        String up = uploadId(expired);
        assertEquals(1, cleanup.runOnce());
        assertEquals("ABORTED", sessionStatus(expired));
        assertEquals("OPEN", sessionStatus(fresh));
        assertTrue(storage.aborted.contains(up));
        assertFalse(storage.uploadOpen(up));
        assertEquals(0, cleanup.runOnce());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String sessionStatus(long sessionId) {
        return jdbc.queryForObject("SELECT status FROM vod_upload_session WHERE id = ?", String.class, sessionId);
    }

    private String uploadId(long sessionId) {
        return jdbc.queryForObject("SELECT multipart_upload_id FROM vod_upload_session WHERE id = ?", String.class, sessionId);
    }

    private JsonNode openMultipart(String idem) throws Exception {
        var req = post("/api/v1/uploads").header("X-Api-Key", "my-a").contentType("application/json").content("{\"mode\":\"multipart\"}");
        if (idem != null) req.header("Idempotency-Key", idem);
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions complete(long sessionId, String body) throws Exception {
        return mvc.perform(post("/api/v1/uploads/" + sessionId + "/complete").header("X-Api-Key", "my-a")
                .contentType("application/json").content(body));
    }
}
