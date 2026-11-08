package vod.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vod.auth.ApiKeys;
import vod.job.OutboxPublisher;
import vod.job.RecordingPublisher;
import vod.storage.MemoryStorage;
import vod.webhook.FakeWebhookIo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadIsolationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryStorage storage;
    @Autowired RecordingPublisher publisher;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired FakeWebhookIo webhooks;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM vod_webhook_delivery");
        jdbc.update("DELETE FROM vod_webhook_endpoint");
        jdbc.update("DELETE FROM vod_usage_daily");
        jdbc.update("DELETE FROM vod_outbox");
        jdbc.update("DELETE FROM vod_transcode_job");
        jdbc.update("DELETE FROM vod_upload_session");
        jdbc.update("DELETE FROM vod_media_asset");
        jdbc.update("DELETE FROM vod_api_credential");
        jdbc.update("DELETE FROM vod_tenant");
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (?,?,?)",
                1, ApiKeys.sha256("key-a"), "upload,asset");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (?,?,?)",
                2, ApiKeys.sha256("key-b"), "upload,asset");
        publisher.messages.clear();
        publisher.result = true;
        webhooks.signatures.clear();
    }

    @Test
    void tenantBCannotReadTenantAAsset() throws Exception {
        JsonNode created = open("key-a", null);
        mvc.perform(get("/api/v1/assets/" + created.get("asset_id").asLong()).header("X-Api-Key", "key-b"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/assets/" + created.get("asset_id").asLong()).header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADING"));
    }

    @Test
    void completeIsIdempotentAndWritesOneOutbox() throws Exception {
        JsonNode created = open("key-a", "up-1");
        long assetId = created.get("asset_id").asLong();
        String key = jdbc.queryForObject("SELECT source_key FROM vod_media_asset WHERE id = ?", String.class, assetId);
        storage.put("vod-source", key);

        JsonNode first = complete("key-a", created.get("session_id").asLong());
        JsonNode second = complete("key-a", created.get("session_id").asLong());
        assertEquals(first.get("job_id").asLong(), second.get("job_id").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
        String payload = jdbc.queryForObject("SELECT payload FROM vod_outbox", String.class);
        org.junit.jupiter.api.Assertions.assertTrue(payload.contains("\"origin\":\"core\"") || payload.contains("\"origin\": \"core\""));
        assertEquals(1, publisher.messages.size());
        org.junit.jupiter.api.Assertions.assertTrue(publisher.messages.get(0).contains("\"origin\":\"core\"") || publisher.messages.get(0).contains("\"origin\": \"core\""));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM vod_outbox", String.class));
        mvc.perform(get("/api/v1/usage").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storage_bytes").value(128))
                .andExpect(jsonPath("$.transcode_jobs").value(1));
        mvc.perform(get("/api/v1/usage").header("X-Api-Key", "key-b"))
                .andExpect(jsonPath("$.transcode_jobs").value(0));

        JsonNode again = open("key-a", "up-1");
        assertEquals(assetId, again.get("asset_id").asLong());
    }

    @Test
    void concurrentCompleteReturnsSameJobAndWritesOneOutbox() throws Exception {
        JsonNode created = open("key-a", "race-1");
        long assetId = created.get("asset_id").asLong();
        long sessionId = created.get("session_id").asLong();
        String key = jdbc.queryForObject("SELECT source_key FROM vod_media_asset WHERE id = ?", String.class, assetId);
        storage.put("vod-source", key);

        int workers = 2;
        var pool = Executors.newFixedThreadPool(workers);
        var ready = new CountDownLatch(workers);
        var go = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return complete("key-a", sessionId);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            JsonNode first = results.get(0).get(15, TimeUnit.SECONDS);
            JsonNode second = results.get(1).get(15, TimeUnit.SECONDS);
            assertEquals(first.get("job_id").asLong(), second.get("job_id").asLong());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM vod_outbox", String.class));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM vod_upload_session WHERE id = ?", String.class, sessionId));
        assertEquals(1, jdbc.queryForObject("SELECT transcode_jobs FROM vod_usage_daily WHERE tenant_id = 1", Integer.class));
        assertEquals(1, publisher.messages.size());
    }

    @Test
    void failedPublishStaysPendingAndOutboxPublisherRetriesOnce() throws Exception {
        JsonNode created = open("key-a", "fail-1");
        long assetId = created.get("asset_id").asLong();
        String key = jdbc.queryForObject("SELECT source_key FROM vod_media_asset WHERE id = ?", String.class, assetId);
        storage.put("vod-source", key);

        publisher.result = false;
        JsonNode completed = complete("key-a", created.get("session_id").asLong());
        assertNotNull(completed.get("job_id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM vod_outbox", String.class));
        assertNull(jdbc.queryForObject("SELECT published_at FROM vod_outbox", Object.class));

        publisher.result = true;
        assertEquals(1, outboxPublisher.publishPending());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM vod_outbox", String.class));
        assertNotNull(jdbc.queryForObject("SELECT published_at FROM vod_outbox", Object.class));
        String lastMessage = publisher.messages.get(publisher.messages.size() - 1);
        assertTrue(lastMessage.contains("\"origin\":\"core\"") || lastMessage.contains("\"origin\": \"core\""));

        publisher.messages.clear();
        assertEquals(0, outboxPublisher.publishPending());
        assertTrue(publisher.messages.isEmpty());
        assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM vod_outbox", String.class));
    }

    @Test
    void missingObjectIsConflictAndCreatesNoJob() throws Exception {
        JsonNode created = open("key-a", null);
        mvc.perform(post("/api/v1/uploads/" + created.get("session_id").asLong() + "/complete")
                        .header("X-Api-Key", "key-a"))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
    }

    @Test
    void otherTenantCannotComplete() throws Exception {
        JsonNode created = open("key-a", null);
        mvc.perform(post("/api/v1/uploads/" + created.get("session_id").asLong() + "/complete")
                        .header("X-Api-Key", "key-b"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingKeyIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/assets/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void playbackRequiresReadyAndOwnTenant() throws Exception {
        JsonNode created = open("key-a", null);
        long assetId = created.get("asset_id").asLong();
        mvc.perform(get("/api/v1/assets/" + assetId + "/playback").header("X-Api-Key", "key-a"))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE vod_media_asset SET status = 'READY' WHERE id = ?", assetId);
        mvc.perform(get("/api/v1/assets/" + assetId + "/playback").header("X-Api-Key", "key-b"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/assets/" + assetId + "/playback").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.master_url").value(org.hamcrest.Matchers.startsWith("/hls/v1/")));
        mvc.perform(get("/api/v1/usage").header("X-Api-Key", "key-a"))
                .andExpect(jsonPath("$.play_requests").value(1));
    }

    @Test
    void webhookRejectsPrivateUrlAndHidesOtherTenant() throws Exception {
        mvc.perform(post("/api/v1/webhooks").header("X-Api-Key", "key-a")
                        .contentType("application/json").content("{\"url\":\"https://127.0.0.1/hook\"}"))
                .andExpect(status().isBadRequest());
        String created = mvc.perform(post("/api/v1/webhooks").header("X-Api-Key", "key-a")
                        .contentType("application/json").content("{\"url\":\"https://hooks.example.com/vod\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(created.contains("secret"));
        mvc.perform(get("/api/v1/webhooks").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhooks[0].url").value("https://hooks.example.com/vod"))
                .andExpect(jsonPath("$.webhooks[0].secret").doesNotExist());
        mvc.perform(get("/api/v1/webhooks").header("X-Api-Key", "key-b"))
                .andExpect(jsonPath("$.webhooks").isEmpty());
    }

    private JsonNode open(String key, String idempotency) throws Exception {
        var req = post("/api/v1/uploads").header("X-Api-Key", key).contentType("application/json").content("{}");
        if (idempotency != null) req.header("Idempotency-Key", idempotency);
        String body = mvc.perform(req).andExpect(status().isOk())
                .andExpect(jsonPath("$.expires_in").value(900))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode complete(String key, long sessionId) throws Exception {
        String body = mvc.perform(post("/api/v1/uploads/" + sessionId + "/complete").header("X-Api-Key", key))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
}
