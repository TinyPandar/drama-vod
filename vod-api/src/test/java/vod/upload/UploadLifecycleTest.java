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
import org.springframework.test.web.servlet.ResultMatcher;
import vod.auth.ApiKeys;
import vod.job.RecordingPublisher;
import vod.storage.MemoryStorage;
import vod.usage.UsageService;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 2 收尾：storage_object、会话过期、HEAD 校验、配额、分页、详情、幂等、key 过期与 scope、单任务查询。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadLifecycleTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryStorage storage;
    @Autowired RecordingPublisher publisher;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String table : List.of("vod_webhook_delivery", "vod_webhook_endpoint", "vod_usage_daily", "vod_outbox",
                "vod_transcode_job", "vod_upload_session", "vod_media_asset", "vod_storage_object",
                "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        credential(1, "key-a", "upload,asset,job", null);
        credential(2, "key-b", "upload,asset,job", null);
        credential(1, "key-a-asset-only", "asset", null);
        credential(1, "key-a-all", "*", null);
        credential(1, "key-a-expired", "*", Instant.now().minusSeconds(60));
        credential(1, "key-a-future", "*", Instant.now().plusSeconds(3600));
        publisher.messages.clear();
        publisher.result = true;
    }

    // 1. storage_object 与资产引用一致，旧列双写可读

    @Test
    void storageObjectAndAssetColumnsStayConsistent() throws Exception {
        JsonNode opened = open("key-a", null, "{}");
        long assetId = opened.get("asset_id").asLong();
        Map<String, Object> asset = jdbc.queryForMap(
                "SELECT source_object_id, source_bucket, source_key FROM vod_media_asset WHERE id = ?", assetId);
        Map<String, Object> object = jdbc.queryForMap(
                "SELECT id, tenant_id, bucket, object_key, role, size FROM vod_storage_object WHERE id = ?",
                asset.get("source_object_id"));
        assertEquals("SOURCE", object.get("role"));
        assertEquals(1L, ((Number) object.get("tenant_id")).longValue());
        assertEquals(asset.get("source_bucket"), object.get("bucket"));
        assertEquals(asset.get("source_key"), object.get("object_key"));
        assertEquals(object.get("object_key"), jdbc.queryForObject(
                "SELECT object_key FROM vod_upload_session WHERE id = ?", String.class, opened.get("session_id").asLong()));
        assertEquals(null, object.get("size"));

        storage.put("vod-source", (String) object.get("object_key"), 256, "video/mp4");
        JsonNode done = complete("key-a", opened.get("session_id").asLong(), status().isOk());

        // Worker 仍按旧列读源文件：这里用它的 JOIN 方式核对与 storage_object 对得上。
        Map<String, Object> workerView = jdbc.queryForMap(
                "SELECT a.source_bucket, a.source_key, a.source_size FROM vod_transcode_job j "
                        + "JOIN vod_media_asset a ON a.id = j.asset_id WHERE j.id = ?", done.get("job_id").asLong());
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT bucket, object_key, size, etag, content_type FROM vod_storage_object WHERE id = ?", object.get("id"));
        assertEquals(after.get("bucket"), workerView.get("source_bucket"));
        assertEquals(after.get("object_key"), workerView.get("source_key"));
        assertEquals(256L, ((Number) after.get("size")).longValue());
        assertEquals(256L, ((Number) workerView.get("source_size")).longValue());
        assertEquals("etag-256", after.get("etag"));
        assertEquals("video/mp4", after.get("content_type"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_storage_object", Integer.class));
    }

    // 2. 会话过期 / 非 OPEN

    @Test
    void openRecordsSessionExpiry() throws Exception {
        Instant before = Instant.now();
        JsonNode opened = open("key-a", null, "{}");
        Timestamp expiresAt = jdbc.queryForObject("SELECT expires_at FROM vod_upload_session WHERE id = ?",
                Timestamp.class, opened.get("session_id").asLong());
        assertTrue(!expiresAt.toInstant().isBefore(before.plusSeconds(899)));
        assertTrue(!expiresAt.toInstant().isAfter(Instant.now().plusSeconds(900)));
        assertEquals(900, opened.get("expires_in").asInt());
        assertNotNull(opened.get("expires_at"));
    }

    @Test
    void expiredSessionCannotComplete() throws Exception {
        JsonNode opened = openAndUpload("key-a", null);
        long sessionId = opened.get("session_id").asLong();
        jdbc.update("UPDATE vod_upload_session SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), sessionId);
        complete("key-a", sessionId, status().isConflict());
        assertNoJob();
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?",
                String.class, opened.get("asset_id").asLong()));
    }

    @Test
    void nonOpenSessionCannotComplete() throws Exception {
        JsonNode opened = openAndUpload("key-a", null);
        long sessionId = opened.get("session_id").asLong();
        jdbc.update("UPDATE vod_upload_session SET status = 'ABORTED' WHERE id = ?", sessionId);
        complete("key-a", sessionId, status().isConflict());
        assertNoJob();
    }

    @Test
    void repeatedCompleteAfterReadyKeepsJobAndDoesNotRegressAsset() throws Exception {
        JsonNode opened = openAndUpload("key-a", null);
        long sessionId = opened.get("session_id").asLong();
        long assetId = opened.get("asset_id").asLong();
        JsonNode first = complete("key-a", sessionId, status().isOk());
        jdbc.update("UPDATE vod_media_asset SET status = 'READY' WHERE id = ?", assetId);
        // 会话已 COMPLETED 且过期，但任务已存在：仍幂等返回同一任务。
        jdbc.update("UPDATE vod_upload_session SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), sessionId);
        JsonNode again = complete("key-a", sessionId, status().isOk());
        assertEquals(first.get("job_id").asLong(), again.get("job_id").asLong());
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, assetId));
    }

    @Test
    void completedSessionReplayDoesNotIssuePutUrl() throws Exception {
        JsonNode opened = open("key-a", "done-1", "{}");
        assertTrue(opened.hasNonNull("put_url"));
        storage.put("vod-source", sourceKey(opened));
        long jobId = complete("key-a", opened.get("session_id").asLong(), status().isOk()).get("job_id").asLong();

        JsonNode replay = open("key-a", "done-1", "{}");
        assertTrue(!replay.has("put_url"), replay.toString());
        assertTrue(!replay.has("expires_in"), replay.toString());
        assertEquals(opened.get("session_id").asLong(), replay.get("session_id").asLong());
        assertEquals(opened.get("asset_id").asLong(), replay.get("asset_id").asLong());
        assertEquals("COMPLETED", replay.get("session_status").asText());
        assertEquals(jobId, replay.get("job_id").asLong());
        assertEquals("PENDING", replay.get("job_status").asText());

        // 会话过期后重放已完成会话：仍只返回信息，不签发 PUT，也不报过期。
        jdbc.update("UPDATE vod_upload_session SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), opened.get("session_id").asLong());
        JsonNode late = open("key-a", "done-1", "{}");
        assertTrue(!late.has("put_url"));
        assertEquals(jobId, late.get("job_id").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_upload_session", Integer.class));
    }

    @Test
    void openSessionReplayStillIssuesPutUrl() throws Exception {
        JsonNode opened = open("key-a", "open-1", "{}");
        JsonNode replay = open("key-a", "open-1", "{}");
        assertEquals(opened.get("session_id").asLong(), replay.get("session_id").asLong());
        assertTrue(replay.get("put_url").asText().contains(sourceKey(opened)));
        assertTrue(replay.get("expires_in").asInt() > 0);
        assertTrue(!replay.has("session_status"));
    }

    // 3. HEAD 校验

    @Test
    void s3BinaryOctetStreamIsAcceptedByDefault() throws Exception {
        JsonNode opened = open("key-a", null, "{}");
        storage.put("vod-source", sourceKey(opened), 128, "binary/octet-stream");
        complete("key-a", opened.get("session_id").asLong(), status().isOk());
    }

    @Test
    void oversizedObjectIsRejectedAndCreatesNoJob() throws Exception {
        JsonNode opened = open("key-a", null, "{}");
        storage.put("vod-source", sourceKey(opened), 2L * 1024 * 1024, "video/mp4");
        complete("key-a", opened.get("session_id").asLong(), status().isPayloadTooLarge());
        assertNoJob();
        assertEquals(null, jdbc.queryForObject("SELECT size FROM vod_storage_object", Long.class));
    }

    @Test
    void wrongContentTypeOrEmptyObjectIsRejected() throws Exception {
        JsonNode opened = open("key-a", null, "{}");
        long sessionId = opened.get("session_id").asLong();
        storage.put("vod-source", sourceKey(opened), 128, "text/plain");
        complete("key-a", sessionId, status().isUnsupportedMediaType());
        storage.put("vod-source", sourceKey(opened), 128, null);
        complete("key-a", sessionId, status().isUnsupportedMediaType());
        storage.put("vod-source", sourceKey(opened), 0, "video/mp4");
        complete("key-a", sessionId, status().isUnprocessableEntity());
        assertNoJob();

        // 重新上传合规对象后，同一个未过期会话仍可 complete。
        storage.put("vod-source", sourceKey(opened), 128, "video/mp4; codecs=avc1");
        complete("key-a", sessionId, status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
    }

    @Test
    void filenameExtensionIsValidatedAndUsedForKey() throws Exception {
        mvc.perform(post("/api/v1/uploads").header("X-Api-Key", "key-a")
                        .contentType("application/json").content("{\"filename\":\"evil.exe\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_media_asset", Integer.class));

        JsonNode opened = open("key-a", null, "{\"filename\":\"clip.MOV\"}");
        assertTrue(sourceKey(opened).endsWith("/source.mov"));
        storage.put("vod-source", sourceKey(opened), 128, "video/quicktime");
        complete("key-a", opened.get("session_id").asLong(), status().isOk());
    }

    // 4. 配额

    @Test
    void tenantStorageQuotaRejectsUploadOverLimit() throws Exception {
        jdbc.update("UPDATE vod_tenant SET storage_quota_bytes = 200 WHERE id = 1");
        JsonNode first = openAndUpload("key-a", null);
        complete("key-a", first.get("session_id").asLong(), status().isOk());
        assertEquals(128L, jdbc.queryForObject("SELECT storage_used_bytes FROM vod_tenant WHERE id = 1", Long.class));

        JsonNode second = openAndUpload("key-a", null);
        mvc.perform(post("/api/v1/uploads/" + second.get("session_id").asLong() + "/complete").header("X-Api-Key", "key-a"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("storage quota exceeded"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
        assertEquals(128L, jdbc.queryForObject("SELECT storage_used_bytes FROM vod_tenant WHERE id = 1", Long.class));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM vod_upload_session WHERE id = ?",
                String.class, second.get("session_id").asLong()));

        // 别的租户不受影响（默认配置不限制）。
        JsonNode other = openAndUpload("key-b", null);
        complete("key-b", other.get("session_id").asLong(), status().isOk());
    }

    @Test
    void defaultQuotaFromConfigAppliesWhenTenantHasNone() {
        UsageService limited = new UsageService(jdbc, 100);
        limited.reserveStorage(1, 60);
        assertThrows(UsageService.QuotaExceededException.class, () -> limited.reserveStorage(1, 60));
        jdbc.update("UPDATE vod_tenant SET storage_quota_bytes = 0 WHERE id = 1");
        limited.reserveStorage(1, 1_000_000);  // 租户显式 0 = 不限制
        UsageService unlimited = new UsageService(jdbc, 0);
        unlimited.reserveStorage(2, Long.MAX_VALUE / 4);
    }

    // 5. 分页

    @Test
    void assetListIsPagedAndTenantScoped() throws Exception {
        List<Long> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) mine.add(open("key-a", null, "{}").get("asset_id").asLong());
        long theirs = open("key-b", null, "{}").get("asset_id").asLong();

        JsonNode page1 = getJson("/api/v1/assets?limit=2", "key-a");
        assertEquals(2, page1.get("assets").size());
        assertEquals(mine.get(2), page1.get("assets").get(0).get("id").asLong());
        assertEquals(mine.get(1), page1.get("assets").get(1).get("id").asLong());
        long cursor = page1.get("next_cursor").asLong();

        JsonNode page2 = getJson("/api/v1/assets?limit=2&cursor=" + cursor, "key-a");
        assertEquals(1, page2.get("assets").size());
        assertEquals(mine.get(0), page2.get("assets").get(0).get("id").asLong());
        assertTrue(page2.get("next_cursor").isNull());

        JsonNode all = getJson("/api/v1/assets", "key-a");
        Set<Long> ids = new HashSet<>();
        all.get("assets").forEach(a -> ids.add(a.get("id").asLong()));
        assertEquals(Set.copyOf(mine), ids);

        JsonNode b = getJson("/api/v1/assets", "key-b");
        assertEquals(1, b.get("assets").size());
        assertEquals(theirs, b.get("assets").get(0).get("id").asLong());

        mvc.perform(get("/api/v1/assets?limit=0").header("X-Api-Key", "key-a")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/assets?cursor=abc").header("X-Api-Key", "key-a")).andExpect(status().isBadRequest());
    }

    // 6. 资产详情

    @Test
    void assetDetailReturnsMediaAndSourceMetadata() throws Exception {
        JsonNode opened = openAndUpload("key-a", null);
        long assetId = opened.get("asset_id").asLong();
        complete("key-a", opened.get("session_id").asLong(), status().isOk());
        jdbc.update("UPDATE vod_media_asset SET status = 'READY', hls_prefix = ?, duration_ms = 8000, width = 720, height = 1280 WHERE id = ?",
                "core/" + assetId + "/", assetId);

        mvc.perform(get("/api/v1/assets/" + assetId).header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.hls_prefix").value("core/" + assetId + "/"))
                .andExpect(jsonPath("$.duration_ms").value(8000))
                .andExpect(jsonPath("$.width").value(720))
                .andExpect(jsonPath("$.height").value(1280))
                .andExpect(jsonPath("$.source_size").value(128))
                .andExpect(jsonPath("$.source.object_key").value(sourceKey(opened)))
                .andExpect(jsonPath("$.source.size").value(128))
                .andExpect(jsonPath("$.source.etag").value("etag-128"))
                .andExpect(jsonPath("$.source.content_type").value("video/mp4"));
        mvc.perform(get("/api/v1/assets/" + assetId).header("X-Api-Key", "key-b"))
                .andExpect(status().isNotFound());
    }

    // 7. Idempotency-Key 并发

    @Test
    void concurrentOpenWithSameIdempotencyKeyCreatesOneSession() throws Exception {
        int workers = 6;
        var pool = Executors.newFixedThreadPool(workers);
        var ready = new CountDownLatch(workers);
        var go = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return open("key-a", "same-key", "{}");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            Set<Long> sessions = new HashSet<>();
            Set<Long> assets = new HashSet<>();
            for (Future<JsonNode> result : results) {
                JsonNode body = result.get(30, TimeUnit.SECONDS);
                sessions.add(body.get("session_id").asLong());
                assets.add(body.get("asset_id").asLong());
            }
            assertEquals(1, sessions.size());
            assertEquals(1, assets.size());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_upload_session WHERE tenant_id = 1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_media_asset WHERE tenant_id = 1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_storage_object WHERE tenant_id = 1", Integer.class));

        // 另一个租户用同一个 key 是独立的。
        JsonNode other = open("key-b", "same-key", "{}");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_upload_session WHERE tenant_id = 2", Integer.class));
        assertTrue(other.get("asset_id").asLong() > 0);
    }

    @Test
    void idempotentReplayOfExpiredSessionIsRejected() throws Exception {
        JsonNode opened = open("key-a", "late", "{}");
        jdbc.update("UPDATE vod_upload_session SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), opened.get("session_id").asLong());
        mvc.perform(post("/api/v1/uploads").header("X-Api-Key", "key-a").header("Idempotency-Key", "late")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_upload_session", Integer.class));
    }

    // 8. key 过期与 scope

    @Test
    void expiredKeyIsUnauthorizedAndFutureExpiryWorks() throws Exception {
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "key-a-expired")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer key-a-expired")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "key-a-future")).andExpect(status().isOk());
        jdbc.update("UPDATE vod_api_credential SET status = 'REVOKED' WHERE key_hash = ?", ApiKeys.sha256("key-a-future"));
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "key-a-future")).andExpect(status().isUnauthorized());
    }

    @Test
    void scopesAreEnforcedPerEndpoint() throws Exception {
        mvc.perform(post("/api/v1/uploads").header("X-Api-Key", "key-a-asset-only")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("insufficient scope"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_upload_session", Integer.class));

        JsonNode opened = openAndUpload("key-a", null);
        mvc.perform(post("/api/v1/uploads/" + opened.get("session_id").asLong() + "/complete")
                        .header("X-Api-Key", "key-a-asset-only"))
                .andExpect(status().isForbidden());
        assertNoJob();
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "key-a-asset-only")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/assets/" + opened.get("asset_id").asLong()).header("X-Api-Key", "key-a-asset-only"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/jobs").header("X-Api-Key", "key-a-asset-only")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/jobs/1").header("X-Api-Key", "key-a-asset-only")).andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/jobs").header("X-Api-Key", "key-a-all")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/uploads").header("X-Api-Key", "key-a-all").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    // 9. 单任务查询

    @Test
    void jobDetailIsTenantScoped() throws Exception {
        JsonNode opened = openAndUpload("key-a", null);
        long jobId = complete("key-a", opened.get("session_id").asLong(), status().isOk()).get("job_id").asLong();
        jdbc.update("UPDATE vod_transcode_job SET attempts = 2, claim_version = 3, last_error = 'ffmpeg exit 1' WHERE id = ?", jobId);

        mvc.perform(get("/api/v1/jobs/" + jobId).header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.asset_id").value(opened.get("asset_id").asLong()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.claim_version").value(3))
                .andExpect(jsonPath("$.last_error").value("ffmpeg exit 1"))
                .andExpect(jsonPath("$.tenant_id").doesNotExist());
        mvc.perform(get("/api/v1/jobs/" + jobId).header("X-Api-Key", "key-b")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/jobs/" + (jobId + 1000)).header("X-Api-Key", "key-a")).andExpect(status().isNotFound());
    }

    private void credential(long tenantId, String raw, String scopes, Instant expiresAt) {
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes, expires_at) VALUES (?,?,?,?)",
                tenantId, ApiKeys.sha256(raw), scopes, expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    private void assertNoJob() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
    }

    private String sourceKey(JsonNode opened) {
        return jdbc.queryForObject("SELECT o.object_key FROM vod_media_asset a JOIN vod_storage_object o "
                + "ON o.id = a.source_object_id WHERE a.id = ?", String.class, opened.get("asset_id").asLong());
    }

    private JsonNode openAndUpload(String key, String idempotency) throws Exception {
        JsonNode opened = open(key, idempotency, "{}");
        storage.put("vod-source", sourceKey(opened));
        return opened;
    }

    private JsonNode open(String key, String idempotency, String body) throws Exception {
        var req = post("/api/v1/uploads").header("X-Api-Key", key).contentType("application/json").content(body);
        if (idempotency != null) req.header("Idempotency-Key", idempotency);
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode complete(String key, long sessionId, ResultMatcher expected) throws Exception {
        String body = mvc.perform(post("/api/v1/uploads/" + sessionId + "/complete").header("X-Api-Key", key))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode getJson(String path, String key) throws Exception {
        return json.readTree(mvc.perform(get(path).header("X-Api-Key", key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
