package vod.asset;

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
import vod.upload.UploadCleanup;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 路线 B：资产删除、上传收尾、源对象清理、保留期清理、标题 / 标签与筛选。 */
@SpringBootTest(properties = {"vod.upload.max-object-bytes=104857600", "vod.upload.put-abandon-grace-seconds=300",
        "vod.upload.assembled-retention-seconds=604800"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssetLifecycleTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryStorage storage;
    @Autowired RecordingPublisher publisher;
    @Autowired UploadCleanup uploadCleanup;
    @Autowired StorageObjectCleanup objectCleanup;
    @Autowired RetentionCleanup retention;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String table : List.of("vod_asset_tag", "vod_content_key", "vod_idempotency_key", "vod_output_prefix",
                "vod_webhook_event", "vod_webhook_delivery", "vod_webhook_endpoint", "vod_usage_daily", "vod_outbox",
                "vod_transcode_job", "vod_upload_session", "vod_media_asset", "vod_storage_object", "vod_api_credential",
                "vod_tenant")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        credential(1, "key-a", "upload,asset,job");
        credential(2, "key-b", "upload,asset,job");
        storage.clear();
        publisher.messages.clear();
        publisher.result = true;
    }

    // ---------- 删除 ----------

    @Test
    void deletingReadyAssetHidesItReleasesQuotaAndSchedulesCleanup() throws Exception {
        long asset = readyAsset("key-a");
        String key = sourceKey(asset);
        long jobId = jdbc.queryForObject("SELECT id FROM vod_transcode_job WHERE asset_id = ?", Long.class, asset);
        jdbc.update("INSERT INTO vod_content_key(asset_id, key_hex, iv_hex) VALUES (?, ?, ?)", asset, "0".repeat(32), "1".repeat(32));
        assertEquals(128L, usedBytes(1));

        JsonNode deleted = call(delete("/api/v1/assets/" + asset), "key-a", status().isOk());
        assertEquals("DELETED", deleted.get("status").asText());
        assertEquals("USER", deleted.get("deleted_reason").asText());
        assertNotNull(deleted.get("deleted_at").asText());

        assertEquals(0L, usedBytes(1), "源文件占用的存储配额退回");
        call(get("/api/v1/assets/" + asset), "key-a", status().isNotFound());
        assertEquals(0, call(get("/api/v1/assets"), "key-a", status().isOk()).get("assets").size());
        assertEquals(1, call(get("/api/v1/assets?status=DELETED"), "key-a", status().isOk()).get("assets").size());
        call(post("/api/v1/assets/" + asset + "/playback-token"), "key-a", status().isNotFound());
        call(get("/api/v1/assets/" + asset + "/playback"), "key-a", status().isNotFound());
        call(post("/api/v1/assets/" + asset + "/transcodes"), "key-a", status().isNotFound());
        callJson(patch("/api/v1/assets/" + asset), "key-a", "{\"title\":\"x\"}", status().isNotFound());
        assertEquals("GARBAGE", jdbc.queryForObject("SELECT status FROM vod_output_prefix WHERE asset_id = ?", String.class, asset));
        assertNull(jdbc.queryForObject("SELECT hls_prefix FROM vod_media_asset WHERE id = ?", String.class, asset));
        assertEquals(0, count("SELECT COUNT(*) FROM vod_content_key WHERE asset_id = " + asset));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE id = ?", String.class, jobId),
                "任务行保留作历史");

        assertTrue(storage.head("vod-source", key).isPresent());
        assertEquals(1, objectCleanup.runOnce());
        assertTrue(storage.head("vod-source", key).isEmpty());
        assertTrue(storage.deleted.contains("vod-source/" + key));
        assertEquals(0, objectCleanup.runOnce(), "删过的不再处理");

        JsonNode again = call(delete("/api/v1/assets/" + asset), "key-a", status().isOk());
        assertEquals("DELETED", again.get("status").asText());
        assertEquals(0L, usedBytes(1), "重复删除不重复退配额");
    }

    @Test
    void deleteIsRefusedWhileAJobIsActiveAndAllowedAfterCancel() throws Exception {
        JsonNode opened = openAndUpload("key-a", "{}");
        JsonNode done = complete("key-a", opened.get("session_id").asLong());
        long asset = opened.get("asset_id").asLong();
        call(delete("/api/v1/assets/" + asset), "key-a", status().isConflict());
        assertEquals("UPLOADED", assetStatus(asset));
        call(post("/api/v1/jobs/" + done.get("job_id").asLong() + "/cancel"), "key-a", status().isOk());
        call(delete("/api/v1/assets/" + asset), "key-a", status().isOk());
        assertEquals("DELETED", assetStatus(asset));
    }

    @Test
    void otherTenantCannotDeleteOrSeeTheAsset() throws Exception {
        long asset = readyAsset("key-a");
        call(delete("/api/v1/assets/" + asset), "key-b", status().isNotFound());
        call(delete("/api/v1/assets/999999"), "key-a", status().isNotFound());
        assertEquals("READY", assetStatus(asset));
    }

    @Test
    void deletingAnUploadingAssetAbortsTheOpenMultipartUpload() throws Exception {
        JsonNode opened = open("key-a", "{\"mode\":\"multipart\"}", status().isOk());
        long asset = opened.get("asset_id").asLong();
        long session = opened.get("session_id").asLong();
        String uploadId = jdbc.queryForObject("SELECT multipart_upload_id FROM vod_upload_session WHERE id = ?", String.class, session);

        storage.failAbort = true;
        call(delete("/api/v1/assets/" + asset), "key-a", status().isServiceUnavailable());
        assertEquals("UPLOADING", assetStatus(asset), "存储不可用时什么都不改");
        assertEquals("OPEN", sessionStatus(session));

        storage.failAbort = false;
        call(delete("/api/v1/assets/" + asset), "key-a", status().isOk());
        assertTrue(storage.aborted.contains(uploadId));
        assertEquals("ABORTED", sessionStatus(session));
        assertEquals("DELETED", assetStatus(asset));
        assertEquals(0L, usedBytes(1));
    }

    @Test
    void retryingAJobOfADeletedAssetIsAConflict() throws Exception {
        long asset = readyAsset("key-a");
        long jobId = jdbc.queryForObject("SELECT id FROM vod_transcode_job WHERE asset_id = ?", Long.class, asset);
        jdbc.update("UPDATE vod_transcode_job SET status = 'FAILED' WHERE id = ?", jobId);
        call(delete("/api/v1/assets/" + asset), "key-a", status().isOk());
        call(post("/api/v1/jobs/" + jobId + "/retry"), "key-a", status().isConflict());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE id = ?", String.class, jobId));
    }

    // ---------- 上传收尾 ----------

    @Test
    void abortingAPutUploadDeletesTheAssetAndItsUploadedObject() throws Exception {
        JsonNode opened = openAndUpload("key-a", "{}");
        long asset = opened.get("asset_id").asLong();
        String key = sourceKey(asset);
        call(post("/api/v1/uploads/" + opened.get("session_id").asLong() + "/abort"), "key-a", status().isOk());
        assertEquals("DELETED", assetStatus(asset));
        assertEquals("UPLOAD_ABORTED", jdbc.queryForObject("SELECT deleted_reason FROM vod_media_asset WHERE id = ?", String.class, asset));
        assertEquals(1, objectCleanup.runOnce());
        assertTrue(storage.head("vod-source", key).isEmpty());
    }

    @Test
    void expiredPutSessionIsCleanedAfterTheGracePeriod() throws Exception {
        JsonNode opened = openAndUpload("key-a", "{}");
        long session = opened.get("session_id").asLong();
        long asset = opened.get("asset_id").asLong();
        String key = sourceKey(asset);

        expire(session, 60);  // 过期 1 分钟，还在 300 秒宽限期里
        assertEquals(0, uploadCleanup.runOnce());
        assertEquals("UPLOADING", assetStatus(asset));

        expire(session, 3600);
        assertEquals(1, uploadCleanup.runOnce());
        assertEquals("ABORTED", sessionStatus(session));
        assertEquals("DELETED", assetStatus(asset));
        assertEquals("UPLOAD_EXPIRED", jdbc.queryForObject("SELECT deleted_reason FROM vod_media_asset WHERE id = ?", String.class, asset));
        assertEquals(1, objectCleanup.runOnce());
        assertTrue(storage.head("vod-source", key).isEmpty());
        call(post("/api/v1/uploads/" + session + "/complete"), "key-a", status().isConflict());
        assertEquals(0, uploadCleanup.runOnce());
    }

    @Test
    void assembledButNeverCompletedMultipartIsKeptForTheRetentionPeriod() throws Exception {
        JsonNode opened = open("key-a", "{\"mode\":\"multipart\"}", status().isOk());
        long session = opened.get("session_id").asLong();
        long asset = opened.get("asset_id").asLong();
        String key = sourceKey(asset);
        storage.put("vod-source", key, 6 * 1024 * 1024, "video/mp4");  // 存储侧已组合

        expire(session, 3600);
        assertEquals(0, uploadCleanup.runOnce(), "保留期内仍允许 complete");
        assertEquals("OPEN", sessionStatus(session));
        assertEquals("UPLOADING", assetStatus(asset));

        jdbc.update("UPDATE vod_upload_session SET cleanup_until = NULL, expires_at = ? WHERE id = ?",
                new Timestamp(System.currentTimeMillis() - 8L * 86_400_000L), session);
        assertEquals(1, uploadCleanup.runOnce());
        assertEquals("ABORTED", sessionStatus(session));
        assertEquals("DELETED", assetStatus(asset));
        assertEquals(1, objectCleanup.runOnce());
        assertTrue(storage.head("vod-source", key).isEmpty());
    }

    // ---------- 源对象清理 ----------

    @Test
    void objectCleanupNeverDeletesSourcesOfLiveAssets() throws Exception {
        long asset = readyAsset("key-a");
        String key = sourceKey(asset);
        jdbc.update("UPDATE vod_storage_object SET delete_after = ? WHERE object_key = ?",
                new Timestamp(System.currentTimeMillis() - 1000), key);
        assertEquals(0, objectCleanup.runOnce());
        assertTrue(storage.head("vod-source", key).isPresent());
    }

    @Test
    void objectCleanupKeepsTheRowWhenStorageFailsAndRetriesLater() throws Exception {
        long asset = readyAsset("key-a");
        String key = sourceKey(asset);
        call(delete("/api/v1/assets/" + asset), "key-a", status().isOk());
        storage.failDelete = true;
        assertEquals(0, objectCleanup.runOnce());
        assertNull(jdbc.queryForObject("SELECT deleted_at FROM vod_storage_object WHERE object_key = ?", Timestamp.class, key));
        storage.failDelete = false;
        assertEquals(0, objectCleanup.runOnce(), "领取未到期");
        jdbc.update("UPDATE vod_storage_object SET cleanup_until = NULL WHERE object_key = ?", key);
        assertEquals(1, objectCleanup.runOnce());
        assertTrue(storage.head("vod-source", key).isEmpty());
    }

    // ---------- 保留期 ----------

    @Test
    void retentionDropsOldFinishedRowsAndKeepsPendingOnes() {
        Timestamp old = new Timestamp(System.currentTimeMillis() - 40L * 86_400_000L);
        Timestamp fresh = new Timestamp(System.currentTimeMillis());
        jdbc.update("INSERT INTO vod_webhook_endpoint(id, tenant_id, url, secret, events) VALUES (1, 1, 'https://e.example/h', 's', '*'), "
                + "(2, 1, 'https://e.example/h2', 's', '*')");
        jdbc.update("INSERT INTO vod_webhook_event(id, tenant_id, event_type, status, created_at) VALUES "
                + "(1, 1, 'transcode.succeeded', 'DISPATCHED', ?), (2, 1, 'transcode.succeeded', 'DISPATCHED', ?), "
                + "(3, 1, 'transcode.succeeded', 'PENDING', ?), (4, 1, 'transcode.succeeded', 'DISPATCHED', ?)", old, old, old, fresh);
        jdbc.update("INSERT INTO vod_webhook_delivery(tenant_id, endpoint_id, event_type, status, event_id, created_at) VALUES "
                + "(1, 1, 'transcode.succeeded', 'DELIVERED', 1, ?), (1, 2, 'transcode.succeeded', 'FAILED', 1, ?), "
                + "(1, 1, 'transcode.succeeded', 'PENDING', 2, ?), (1, 1, 'transcode.succeeded', 'DELIVERED', 4, ?)", old, old, old, fresh);
        jdbc.update("INSERT INTO vod_outbox(tenant_id, event_type, payload, status, published_at) VALUES "
                + "(1, 'transcode.requested', '{}', 'PUBLISHED', ?), (1, 'transcode.requested', '{}', 'PENDING', NULL), "
                + "(1, 'transcode.requested', '{}', 'PUBLISHED', ?)", old, fresh);
        jdbc.update("INSERT INTO vod_idempotency_key(tenant_id, operation, idem_key, fingerprint, created_at) VALUES "
                + "(1, 'transcode', 'old', 'f', ?), (1, 'transcode', 'new', 'f', ?)", old, fresh);

        var removed = retention.runOnce();
        assertEquals(2, removed.get("webhook_delivery"));
        assertEquals(1, removed.get("webhook_event"), "事件 1 的投递删完后可删；事件 2 还有 PENDING 投递；3 是 PENDING；4 太新");
        assertEquals(1, removed.get("outbox"));
        assertEquals(1, removed.get("idempotency_key"));
        assertEquals(List.of(2L, 3L, 4L), jdbc.queryForList("SELECT id FROM vod_webhook_event ORDER BY id", Long.class));
        assertEquals(List.of("PENDING", "DELIVERED"),
                jdbc.queryForList("SELECT status FROM vod_webhook_delivery ORDER BY id", String.class));
        assertEquals(2, count("SELECT COUNT(*) FROM vod_outbox"));
        assertEquals(List.of("new"), jdbc.queryForList("SELECT idem_key FROM vod_idempotency_key", String.class));
        var second = retention.runOnce();
        assertTrue(second.values().stream().allMatch(n -> n == 0), second.toString());
    }

    // ---------- 标题、标签与筛选 ----------

    @Test
    void titleAndTagsAreStoredReturnedAndEditable() throws Exception {
        JsonNode opened = open("key-a", "{\"title\":\"  第 1 集 · 重逢 \",\"tags\":[\"短剧\",\"ep-1\",\"短剧\"]}", status().isOk());
        long asset = opened.get("asset_id").asLong();
        JsonNode got = call(get("/api/v1/assets/" + asset), "key-a", status().isOk());
        assertEquals("第 1 集 · 重逢", got.get("title").asText());
        assertEquals(List.of("短剧", "ep-1"), texts(got.get("tags")));
        assertNotNull(got.get("created_at").asText());

        JsonNode patched = callJson(patch("/api/v1/assets/" + asset), "key-a", "{\"tags\":[\"final\"]}", status().isOk());
        assertEquals("第 1 集 · 重逢", patched.get("title").asText(), "没给的字段不改");
        assertEquals(List.of("final"), texts(patched.get("tags")));
        patched = callJson(patch("/api/v1/assets/" + asset), "key-a", "{\"title\":\"\"}", status().isOk());
        assertTrue(patched.get("title").isNull(), "空标题表示清空");
        assertNotNull(patched.get("updated_at").asText());

        callJson(patch("/api/v1/assets/" + asset), "key-a", "{\"name\":\"x\"}", status().isBadRequest());
        callJson(patch("/api/v1/assets/" + asset), "key-a", "{}", status().isBadRequest());
        callJson(patch("/api/v1/assets/" + asset), "key-b", "{\"title\":\"x\"}", status().isNotFound());
        open("key-a", "{\"tags\":[\"has space\"]}", status().isBadRequest());
        open("key-a", "{\"tags\":[\"" + "x".repeat(33) + "\"]}", status().isBadRequest());
        open("key-a", "{\"tags\":\"one\"}", status().isBadRequest());
        open("key-a", "{\"title\":\"" + "t".repeat(201) + "\"}", status().isBadRequest());
        var many = new ArrayList<String>();
        for (int i = 0; i < 11; i++) many.add("\"t" + i + "\"");
        open("key-a", "{\"tags\":[" + String.join(",", many) + "]}", status().isBadRequest());
    }

    @Test
    void listCanBeFilteredByStatusTitleIdAndTag() throws Exception {
        long a1 = open("key-a", "{\"title\":\"重逢 第一集\",\"tags\":[\"drama\"]}", status().isOk()).get("asset_id").asLong();
        long a2 = open("key-a", "{\"title\":\"100% 真实\",\"tags\":[\"doc\"]}", status().isOk()).get("asset_id").asLong();
        long a3 = readyAsset("key-a");
        long b1 = open("key-b", "{\"title\":\"重逢\",\"tags\":[\"drama\"]}", status().isOk()).get("asset_id").asLong();

        assertEquals(List.of(a3, a2, a1), ids(call(get("/api/v1/assets"), "key-a", status().isOk())));
        assertEquals(List.of(a1), ids(call(get("/api/v1/assets").param("q", "重逢"), "key-a", status().isOk())), "不跨租户");
        assertEquals(List.of(a2), ids(call(get("/api/v1/assets").param("q", "100%"), "key-a", status().isOk())));
        assertEquals(List.of(), ids(call(get("/api/v1/assets").param("q", "0%真"), "key-a", status().isOk())), "% 按字面匹配");
        assertEquals(List.of(a3), ids(call(get("/api/v1/assets").param("q", String.valueOf(a3)), "key-a", status().isOk())));
        assertEquals(List.of(a1), ids(call(get("/api/v1/assets").param("tag", "drama"), "key-a", status().isOk())));
        assertEquals(List.of(a3), ids(call(get("/api/v1/assets").param("status", "ready"), "key-a", status().isOk())));
        assertEquals(List.of("drama"), texts(call(get("/api/v1/assets").param("tag", "drama"), "key-a", status().isOk())
                .get("assets").get(0).get("tags")));
        call(get("/api/v1/assets").param("status", "NOPE"), "key-a", status().isBadRequest());
        call(get("/api/v1/assets").param("tag", "bad tag"), "key-a", status().isBadRequest());
        assertEquals(List.of(b1), ids(call(get("/api/v1/assets").param("tag", "drama"), "key-b", status().isOk())));

        JsonNode page = call(get("/api/v1/assets").param("limit", "1").param("q", "重逢").param("status", "UPLOADING"), "key-a", status().isOk());
        assertEquals(List.of(a1), ids(page));
        assertTrue(page.get("next_cursor").isNull());
    }

    // ---------- helpers ----------

    /** 上传 + complete，再按 Worker 成功事务的样子把资产改成 READY（任务专属前缀 ACTIVE）。 */
    private long readyAsset(String key) throws Exception {
        JsonNode opened = openAndUpload(key, "{}");
        JsonNode done = complete(key, opened.get("session_id").asLong());
        long asset = opened.get("asset_id").asLong();
        long job = done.get("job_id").asLong();
        String prefix = "assets/v1/" + asset + "/" + job + "-1/";
        jdbc.update("UPDATE vod_transcode_job SET status = 'SUCCEEDED', claim_version = 1 WHERE id = ?", job);
        jdbc.update("UPDATE vod_media_asset SET status = 'READY', hls_prefix = ?, poster_key = ? WHERE id = ?", prefix, prefix + "poster.jpg", asset);
        jdbc.update("INSERT INTO vod_output_prefix(asset_id, job_id, claim_version, prefix, status) VALUES (?, ?, 1, ?, 'ACTIVE')",
                asset, job, prefix);
        return asset;
    }

    private JsonNode openAndUpload(String key, String body) throws Exception {
        JsonNode opened = open(key, body, status().isOk());
        storage.put("vod-source", sourceKey(opened.get("asset_id").asLong()));
        return opened;
    }

    private JsonNode open(String key, String body, ResultMatcher expected) throws Exception {
        return callJson(post("/api/v1/uploads"), key, body, expected);
    }

    private JsonNode complete(String key, long session) throws Exception {
        return call(post("/api/v1/uploads/" + session + "/complete"), key, status().isOk());
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String key,
                          ResultMatcher expected) throws Exception {
        String body = mvc.perform(req.header("X-Api-Key", key)).andExpect(expected).andReturn().getResponse().getContentAsString();
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private JsonNode callJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String key, String body,
                              ResultMatcher expected) throws Exception {
        return call(req.contentType("application/json").content(body), key, expected);
    }

    private void expire(long session, long secondsAgo) {
        jdbc.update("UPDATE vod_upload_session SET cleanup_until = NULL, expires_at = ? WHERE id = ?",
                new Timestamp(System.currentTimeMillis() - secondsAgo * 1000L), session);
    }

    private String sourceKey(long asset) {
        return jdbc.queryForObject("SELECT o.object_key FROM vod_media_asset a JOIN vod_storage_object o ON o.id = a.source_object_id "
                + "WHERE a.id = ?", String.class, asset);
    }

    private String assetStatus(long asset) {
        return jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, asset);
    }

    private String sessionStatus(long session) {
        return jdbc.queryForObject("SELECT status FROM vod_upload_session WHERE id = ?", String.class, session);
    }

    private long usedBytes(long tenant) {
        return jdbc.queryForObject("SELECT storage_used_bytes FROM vod_tenant WHERE id = ?", Long.class, tenant);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static List<Long> ids(JsonNode page) {
        var out = new ArrayList<Long>();
        page.get("assets").forEach(a -> out.add(a.get("id").asLong()));
        return out;
    }

    private static List<String> texts(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private void credential(long tenantId, String raw, String scopes) {
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (?,?,?)", tenantId, ApiKeys.sha256(raw), scopes);
    }
}
