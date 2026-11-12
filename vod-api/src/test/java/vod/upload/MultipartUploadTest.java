package vod.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 6：multipart 上传会话（MemoryStorage 模拟 S3 Multipart）。单次 PUT 的原有用例在 UploadLifecycleTest。 */
@SpringBootTest(properties = "vod.upload.max-object-bytes=104857600")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultipartUploadTest {
    private static final long MIB = 1024 * 1024;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryStorage storage;
    @Autowired RecordingPublisher publisher;
    @Autowired UploadCleanup cleanup;
    @Autowired MeterRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String table : List.of("vod_webhook_event", "vod_webhook_delivery", "vod_webhook_endpoint", "vod_usage_daily",
                "vod_outbox", "vod_transcode_job", "vod_upload_session", "vod_media_asset", "vod_storage_object",
                "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        credential(1, "key-a", "upload,asset,job");
        credential(2, "key-b", "upload,asset,job");
        storage.clear();
        publisher.messages.clear();
        publisher.result = true;
    }

    // ---- 单次 PUT 保持默认且响应不变 ----

    @Test
    void defaultAndExplicitPutKeepOriginalResponseShape() throws Exception {
        for (String body : List.of("{}", "{\"mode\":\"put\"}")) {
            JsonNode opened = open("key-a", null, body, status().isOk());
            Set<String> fields = new TreeSet<>();
            opened.fieldNames().forEachRemaining(fields::add);
            assertEquals(new TreeSet<>(List.of("session_id", "asset_id", "put_url", "expires_in", "expires_at")), fields, body);
            assertEquals("put", jdbc.queryForObject("SELECT upload_mode FROM vod_upload_session WHERE id = ?",
                    String.class, opened.get("session_id").asLong()));
        }
        open("key-a", null, "{\"mode\":\"chunked\"}", status().isBadRequest());
    }

    @Test
    void configExposesPartSizeAndThreshold() throws Exception {
        JsonNode cfg = json.readTree(mvc.perform(get("/api/v1/uploads/config").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(5 * MIB, cfg.get("part_size_bytes").asLong());
        assertEquals(5 * MIB, cfg.get("multipart_threshold_bytes").asLong());
        assertEquals(10000, cfg.get("max_parts").asInt());
    }

    // ---- 创建 / 重放 ----

    @Test
    void openMultipartCreatesStorageUploadAndNoPutUrl() throws Exception {
        JsonNode opened = openMultipart("key-a", null);
        assertFalse(opened.has("put_url"), opened.toString());
        assertEquals("multipart", opened.get("mode").asText());
        assertEquals(5 * MIB, opened.get("part_size_bytes").asLong());
        assertTrue(opened.get("expires_in").asInt() > 900, "multipart TTL 单独配置");
        var row = jdbc.queryForMap("SELECT upload_mode, multipart_upload_id, object_key FROM vod_upload_session WHERE id = ?",
                opened.get("session_id").asLong());
        assertEquals("multipart", row.get("upload_mode"));
        assertTrue(storage.uploadOpen((String) row.get("multipart_upload_id")));
        assertEquals("raw/t1/" + opened.get("asset_id").asLong() + "/source.mp4", row.get("object_key"));
    }

    @Test
    void idempotentReplayDoesNotCreateSecondStorageUpload() throws Exception {
        JsonNode first = openMultipart("key-a", "mp-1");
        JsonNode again = openMultipart("key-a", "mp-1");
        assertEquals(first.get("session_id").asLong(), again.get("session_id").asLong());
        assertEquals("multipart", again.get("mode").asText());
        assertFalse(again.has("put_url"));
        assertEquals(1, storage.openUploads());
        // 重放不看本次请求的 mode：不带 mode 也回原 multipart 会话，不签 put_url。
        JsonNode plain = open("key-a", "mp-1", "{}", status().isOk());
        assertEquals("multipart", plain.get("mode").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_upload_session", Integer.class));
    }

    @Test
    void declaredContentTypeMustBeAllowed() throws Exception {
        open("key-a", null, "{\"mode\":\"multipart\",\"content_type\":\"text/html\"}", status().isUnsupportedMediaType());
        assertEquals(0, storage.openUploads());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_media_asset", Integer.class));
    }

    // ---- 分片签发 / 断点续传 / complete ----

    @Test
    void resumeListsUploadedPartsThenCompleteCreatesJob() throws Exception {
        JsonNode opened = openMultipart("key-a", null);
        long sessionId = opened.get("session_id").asLong();
        String uploadId = uploadId(sessionId);

        JsonNode urls = parts("key-a", sessionId, "[3,1,2,1]", status().isOk());
        assertEquals(3, urls.get("parts").size());
        assertEquals(1, urls.get("parts").get(0).get("part_number").asInt());
        assertTrue(urls.get("parts").get(0).get("url").asText().contains("partNumber=1"));
        assertTrue(urls.get("expires_in").asInt() <= 900);

        // 传了 1、2 后「断线」：列出已传分片，只补 3。
        String e1 = storage.uploadPart(uploadId, 1, 5 * MIB);
        String e2 = storage.uploadPart(uploadId, 2, 5 * MIB);
        JsonNode listed = listParts("key-a", sessionId, status().isOk());
        assertEquals(2, listed.get("parts").size());
        assertEquals(e2, listed.get("parts").get(1).get("etag").asText());
        assertEquals(5 * MIB, listed.get("parts").get(0).get("size").asLong());
        parts("key-a", sessionId, "[3]", status().isOk());
        String e3 = storage.uploadPart(uploadId, 3, 1234);

        JsonNode done = complete("key-a", sessionId, partsBody(e1, e2, e3), status().isOk());
        assertEquals("PENDING", done.get("job_status").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
        assertEquals("COMPLETED", sessionStatus(sessionId));
        long size = 10 * MIB + 1234;
        assertEquals(size, jdbc.queryForObject("SELECT source_size FROM vod_media_asset WHERE id = ?", Long.class,
                opened.get("asset_id").asLong()));
        // multipart 的 ETag 不是整文件 MD5、也没有 SHA-256：不因缺 checksum 拒绝。
        assertNull(jdbc.queryForObject("SELECT checksum FROM vod_storage_object", String.class));
        assertFalse(storage.uploadOpen(uploadId));

        // 重复 complete 返回同一任务；完成后不能再签分片、不能 abort。
        JsonNode again = complete("key-a", sessionId, partsBody(e1, e2, e3), status().isOk());
        assertEquals(done.get("job_id").asLong(), again.get("job_id").asLong());
        parts("key-a", sessionId, "[1]", status().isConflict());
        abort("key-a", sessionId, status().isConflict());
        assertEquals("COMPLETED", sessionStatus(sessionId));
        // 已完成的会话重放 Idempotency-Key 也不再签任何上传地址
        assertTrue(counter("completed") >= 1);
    }

    @Test
    void missingPartOrWrongEtagDoesNotCreateJobAndCompleteCanBeRetried() throws Exception {
        JsonNode opened = openMultipart("key-a", null);
        long sessionId = opened.get("session_id").asLong();
        String uploadId = uploadId(sessionId);
        String e1 = storage.uploadPart(uploadId, 1, 5 * MIB);
        String e2 = storage.uploadPart(uploadId, 2, 100);

        complete("key-a", sessionId, "{}", status().isBadRequest());                         // 没给 parts
        complete("key-a", sessionId, "{\"parts\":[{\"part_number\":1}]}", status().isBadRequest());  // 没给 etag
        complete("key-a", sessionId, "{\"parts\":[{\"part_number\":1,\"etag\":\"bogus\"},{\"part_number\":2,\"etag\":\"" + e2 + "\"}]}",
                status().isBadRequest());
        complete("key-a", sessionId, partsBody(e1, e2, "missing-3"), status().isBadRequest());
        complete("key-a", sessionId, "{\"parts\":[{\"part_number\":2,\"etag\":\"" + e2 + "\"},{\"part_number\":1,\"etag\":\"" + e1 + "\"}]}",
                status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class));
        assertEquals("OPEN", sessionStatus(sessionId));
        assertTrue(storage.uploadOpen(uploadId), "组合失败不动存储侧上传");

        // 改正后重试成功（etag 带引号也行）
        complete("key-a", sessionId, "{\"parts\":[{\"part_number\":1,\"etag\":\"\\\"" + e1 + "\\\"\"},{\"part_number\":2,\"etag\":\"" + e2 + "\"}]}",
                status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertTrue(counter("complete_rejected") >= 3);
    }

    @Test
    void smallNonLastPartIsRejected() throws Exception {
        long sessionId = openMultipart("key-a", null).get("session_id").asLong();
        String uploadId = uploadId(sessionId);
        String e1 = storage.uploadPart(uploadId, 1, 1000);
        String e2 = storage.uploadPart(uploadId, 2, 1000);
        complete("key-a", sessionId, partsBody(e1, e2), status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
        assertEquals("OPEN", sessionStatus(sessionId));
    }

    @Test
    void assembledButRejectedByPolicyKeepsSessionOpenAndRetryUsesHead() throws Exception {
        // 存储配额 1 字节：组合成功、HEAD 通过，但配额 403；会话仍 OPEN，存储侧 upload 已不存在。
        jdbc.update("UPDATE vod_tenant SET storage_quota_bytes = 1 WHERE id = 1");
        long sessionId = openMultipart("key-a", null).get("session_id").asLong();
        String uploadId = uploadId(sessionId);
        String e1 = storage.uploadPart(uploadId, 1, 4096);
        complete("key-a", sessionId, partsBody(e1), status().isForbidden());
        assertEquals("OPEN", sessionStatus(sessionId));
        assertFalse(storage.uploadOpen(uploadId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));

        // 放开配额后重试：NoSuchUpload → 桶里已有对象 → 继续 HEAD 校验与建任务。
        jdbc.update("UPDATE vod_tenant SET storage_quota_bytes = NULL WHERE id = 1");
        complete("key-a", sessionId, partsBody(e1), status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
    }

    @Test
    void putSessionRejectsPartEndpointsWith409() throws Exception {
        long sessionId = open("key-a", null, "{}", status().isOk()).get("session_id").asLong();
        parts("key-a", sessionId, "[1]", status().isConflict());
        listParts("key-a", sessionId, status().isConflict());
        // put 会话 complete 仍按原逻辑（对象不存在 409），body 里的 parts 被忽略。
        complete("key-a", sessionId, partsBody("x"), status().isConflict());
    }

    @Test
    void partNumbersAreValidated() throws Exception {
        long sessionId = openMultipart("key-a", null).get("session_id").asLong();
        parts("key-a", sessionId, "[]", status().isBadRequest());
        parts("key-a", sessionId, "[0]", status().isBadRequest());
        parts("key-a", sessionId, "[10001]", status().isBadRequest());
        parts("key-a", sessionId, "[\"1\"]", status().isBadRequest());
        var many = new ArrayList<Integer>();
        for (int i = 1; i <= 101; i++) many.add(i);
        parts("key-a", sessionId, many.toString(), status().isBadRequest());
    }

    @Test
    void expiredSessionCannotSignListOrComplete() throws Exception {
        long sessionId = openMultipart("key-a", null).get("session_id").asLong();
        String e1 = storage.uploadPart(uploadId(sessionId), 1, 100);
        expire(sessionId);
        parts("key-a", sessionId, "[1]", status().isConflict());
        listParts("key-a", sessionId, status().isConflict());
        complete("key-a", sessionId, partsBody(e1), status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
    }

    @Test
    void partUrlTtlDoesNotOutliveSession() throws Exception {
        long sessionId = openMultipart("key-a", null).get("session_id").asLong();
        jdbc.update("UPDATE vod_upload_session SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(30)), sessionId);
        int ttl = parts("key-a", sessionId, "[1]", status().isOk()).get("expires_in").asInt();
        assertTrue(ttl <= 30 && ttl > 0, "ttl=" + ttl);
    }

    // ---- 跨租户 ----

    @Test
    void otherTenantGets404Everywhere() throws Exception {
        long sessionId = openMultipart("key-a", null).get("session_id").asLong();
        String uploadId = uploadId(sessionId);
        String e1 = storage.uploadPart(uploadId, 1, 100);
        parts("key-b", sessionId, "[1]", status().isNotFound());
        listParts("key-b", sessionId, status().isNotFound());
        complete("key-b", sessionId, partsBody(e1), status().isNotFound());
        abort("key-b", sessionId, status().isNotFound());
        parts("key-a", sessionId + 999, "[1]", status().isNotFound());
        assertTrue(storage.uploadOpen(uploadId));
        assertEquals("OPEN", sessionStatus(sessionId));
    }

    // ---- abort ----

    @Test
    void abortCancelsStorageUploadAndBlocksFurtherUse() throws Exception {
        JsonNode opened = openMultipart("key-a", "ab-1");
        long sessionId = opened.get("session_id").asLong();
        String uploadId = uploadId(sessionId);
        String e1 = storage.uploadPart(uploadId, 1, 100);
        JsonNode res = abort("key-a", sessionId, status().isOk());
        assertEquals("ABORTED", res.get("session_status").asText());
        assertTrue(storage.aborted.contains(uploadId));
        assertFalse(storage.uploadOpen(uploadId));
        assertEquals("ABORTED", sessionStatus(sessionId));
        assertEquals("UPLOADING", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class,
                opened.get("asset_id").asLong()));
        abort("key-a", sessionId, status().isOk());  // 重复取消幂等
        complete("key-a", sessionId, partsBody(e1), status().isConflict());
        parts("key-a", sessionId, "[2]", status().isConflict());
        // 重放 key：会话已非 OPEN，只回信息
        JsonNode replay = openMultipart("key-a", "ab-1");
        assertEquals("ABORTED", replay.get("session_status").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job", Integer.class));
    }

    @Test
    void abortPutSessionAndStorageFailureKeepsOpen() throws Exception {
        long putSession = open("key-a", null, "{}", status().isOk()).get("session_id").asLong();
        abort("key-a", putSession, status().isOk());
        assertEquals("ABORTED", sessionStatus(putSession));

        long sessionId = openMultipart("key-a", null).get("session_id").asLong();
        storage.failAbort = true;
        mvc.perform(post("/api/v1/uploads/" + sessionId + "/abort").header("X-Api-Key", "key-a"))
                .andExpect(status().isServiceUnavailable());
        assertEquals("OPEN", sessionStatus(sessionId));
        storage.failAbort = false;
        abort("key-a", sessionId, status().isOk());
    }

    // ---- 过期清理 ----

    @Test
    void cleanupAbortsOnlyExpiredOpenMultipartSessions() throws Exception {
        long expired = openMultipart("key-a", null).get("session_id").asLong();
        long fresh = openMultipart("key-a", null).get("session_id").asLong();
        long expiredPut = open("key-a", null, "{}", status().isOk()).get("session_id").asLong();
        String expiredUpload = uploadId(expired);
        String freshUpload = uploadId(fresh);
        storage.uploadPart(expiredUpload, 1, 100);

        // 已完成的会话即使过期也不碰
        long done = openMultipart("key-a", null).get("session_id").asLong();
        String doneUpload = uploadId(done);
        String e = storage.uploadPart(doneUpload, 1, 100);
        complete("key-a", done, partsBody(e), status().isOk());
        String doneKey = jdbc.queryForObject("SELECT object_key FROM vod_upload_session WHERE id = ?", String.class, done);

        expire(expired);
        expire(expiredPut);
        expire(done);

        // 存储失败：保持 OPEN，留着领取，到期前不重复处理；到期后下一轮成功。
        storage.failAbort = true;
        assertEquals(0, cleanup.runOnce());
        assertEquals("OPEN", sessionStatus(expired));
        storage.failAbort = false;
        assertEquals(0, cleanup.runOnce(), "领取未到期，别的轮次跳过");
        jdbc.update("UPDATE vod_upload_session SET cleanup_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), expired);
        assertEquals(1, cleanup.runOnce());

        assertEquals("ABORTED", sessionStatus(expired));
        assertTrue(storage.aborted.contains(expiredUpload));
        assertEquals("OPEN", sessionStatus(fresh));
        assertTrue(storage.uploadOpen(freshUpload));
        assertEquals("OPEN", sessionStatus(expiredPut), "put 会话不处理");
        assertEquals("COMPLETED", sessionStatus(done));
        assertTrue(storage.head("vod-source", doneKey).isPresent(), "已校验的源对象不删");
        assertEquals(0, cleanup.runOnce());
        assertTrue(counter("expired_aborted") >= 1);
        assertTrue(counter("cleanup_failed") >= 1);
    }

    @Test
    void concurrentCleanupClaimsOnce() throws Exception {
        long expired = openMultipart("key-a", null).get("session_id").asLong();
        expire(expired);
        // 另一实例已领取（cleanup_until 未到期）：本实例不处理
        jdbc.update("UPDATE vod_upload_session SET cleanup_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(60)), expired);
        assertEquals(0, cleanup.runOnce());
        assertEquals("OPEN", sessionStatus(expired));
        assertTrue(storage.uploadOpen(uploadId(expired)));
    }

    // ---- helpers ----

    private double counter(String event) {
        var c = registry.find("vod.upload.multipart").tag("event", event).counter();
        return c == null ? 0 : c.count();
    }

    private void expire(long sessionId) {
        jdbc.update("UPDATE vod_upload_session SET expires_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(5)), sessionId);
    }

    private String sessionStatus(long sessionId) {
        return jdbc.queryForObject("SELECT status FROM vod_upload_session WHERE id = ?", String.class, sessionId);
    }

    private String uploadId(long sessionId) {
        return jdbc.queryForObject("SELECT multipart_upload_id FROM vod_upload_session WHERE id = ?", String.class, sessionId);
    }

    private static String partsBody(String... etags) {
        var sb = new StringBuilder("{\"parts\":[");
        for (int i = 0; i < etags.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"part_number\":").append(i + 1).append(",\"etag\":\"").append(etags[i]).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private void credential(long tenantId, String raw, String scopes) {
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (?,?,?)",
                tenantId, ApiKeys.sha256(raw), scopes);
    }

    private JsonNode openMultipart(String key, String idem) throws Exception {
        return open(key, idem, "{\"mode\":\"multipart\"}", status().isOk());
    }

    private JsonNode open(String key, String idem, String body, ResultMatcher expected) throws Exception {
        var req = post("/api/v1/uploads").header("X-Api-Key", key).contentType("application/json").content(body);
        if (idem != null) req.header("Idempotency-Key", idem);
        return read(mvc.perform(req).andExpect(expected).andReturn().getResponse().getContentAsString());
    }

    private JsonNode parts(String key, long sessionId, String numbers, ResultMatcher expected) throws Exception {
        return read(mvc.perform(post("/api/v1/uploads/" + sessionId + "/parts").header("X-Api-Key", key)
                        .contentType("application/json").content("{\"part_numbers\":" + numbers + "}"))
                .andExpect(expected).andReturn().getResponse().getContentAsString());
    }

    private JsonNode listParts(String key, long sessionId, ResultMatcher expected) throws Exception {
        return read(mvc.perform(get("/api/v1/uploads/" + sessionId + "/parts").header("X-Api-Key", key))
                .andExpect(expected).andReturn().getResponse().getContentAsString());
    }

    private JsonNode complete(String key, long sessionId, String body, ResultMatcher expected) throws Exception {
        return read(mvc.perform(post("/api/v1/uploads/" + sessionId + "/complete").header("X-Api-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(expected).andReturn().getResponse().getContentAsString());
    }

    private JsonNode abort(String key, long sessionId, ResultMatcher expected) throws Exception {
        return read(mvc.perform(post("/api/v1/uploads/" + sessionId + "/abort").header("X-Api-Key", key))
                .andExpect(expected).andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(String body) throws Exception {
        return body == null || body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
