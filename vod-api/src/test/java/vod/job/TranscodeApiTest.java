package vod.job;

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
import org.springframework.test.web.servlet.ResultActions;
import vod.auth.ApiKeys;
import vod.storage.MemoryStorage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 5 第 5、6、7（配额）、8 项：重新转码、重试、并发/每日配额、用量。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TranscodeApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryStorage storage;
    @Autowired RecordingPublisher publisher;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_idempotency_key", "vod_webhook_event", "vod_output_prefix", "vod_outbox", "vod_transcode_job",
                "vod_upload_session", "vod_media_asset", "vod_storage_object", "vod_usage_daily", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("DELETE FROM vod_transcode_template WHERE tenant_id <> 0");
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, '*')", ApiKeys.sha256("key-a"));
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (2, ?, '*')", ApiKeys.sha256("key-b"));
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, 'upload')", ApiKeys.sha256("key-a-upload"));
        publisher.messages.clear();
        publisher.result = true;
    }

    /** 走真实 complete 建一个资产和默认任务（PENDING）。返回 [assetId, jobId]。 */
    long[] uploaded(String key) throws Exception {
        JsonNode opened = body(mvc.perform(post("/api/v1/uploads").header("X-Api-Key", key).contentType("application/json").content("{}")));
        long assetId = opened.get("asset_id").asLong();
        storage.put("vod-source", jdbc.queryForObject("SELECT source_key FROM vod_media_asset WHERE id = ?", String.class, assetId));
        JsonNode done = body(mvc.perform(post("/api/v1/uploads/" + opened.get("session_id").asLong() + "/complete").header("X-Api-Key", key))
                .andExpect(status().isOk()));
        return new long[]{assetId, done.get("job_id").asLong()};
    }

    @Test
    void defaultTemplateMatchesFrozenLadder() throws Exception {
        String ladder = jdbc.queryForObject("SELECT ladder_json FROM vod_transcode_template WHERE tenant_id = 0 AND name = 'ladder_v1'", String.class);
        assertEquals("[[\"720p\",720,2500],[\"540p\",540,1200],[\"360p\",360,600]]", ladder);
        mvc.perform(get("/api/v1/templates").header("X-Api-Key", "key-a"))
                .andExpect(jsonPath("$.templates[0].name").value("ladder_v1"))
                .andExpect(jsonPath("$.templates[0].system").value(true))
                .andExpect(jsonPath("$.templates[0].audio_kbps").value(96));
    }

    @Test
    void retranscodeReusesRowBumpsClaimVersionAndKeepsReadyAsset() throws Exception {
        long[] ids = uploaded("key-a");
        long assetId = ids[0], jobId = ids[1];
        // 任务还在 PENDING：409，不新建行
        mvc.perform(post("/api/v1/assets/" + assetId + "/transcodes").header("X-Api-Key", "key-a")).andExpect(status().isConflict());
        for (String active : List.of("RETRYING", "RUNNING")) {
            jdbc.update("UPDATE vod_transcode_job SET status = ? WHERE id = ?", active, jobId);
            mvc.perform(post("/api/v1/assets/" + assetId + "/transcodes").header("X-Api-Key", "key-a")).andExpect(status().isConflict());
        }
        // 模拟 Worker 成功：claim_version=3、资产 READY、前缀 ACTIVE
        jdbc.update("UPDATE vod_transcode_job SET status = 'SUCCEEDED', claim_version = 3, attempts = 2, error_code = 'X' WHERE id = ?", jobId);
        jdbc.update("UPDATE vod_media_asset SET status = 'READY', hls_prefix = ? WHERE id = ?", "assets/v1/" + assetId + "/" + jobId + "-3/", assetId);
        int outboxBefore = count("vod_outbox");
        mvc.perform(post("/api/v1/assets/" + assetId + "/transcodes").header("X-Api-Key", "key-a").header("Idempotency-Key", "re-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.claim_version").value(4))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.error_code").isEmpty())
                .andExpect(jsonPath("$.idempotent_replay").value(false));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job WHERE asset_id = ?", Integer.class, assetId));
        assertEquals(outboxBefore + 1, count("vod_outbox"));
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, assetId));
        String last = publisher.messages.get(publisher.messages.size() - 1);
        JsonNode msg = json.readTree(last);
        assertEquals(1, msg.get("schema_version").asInt());
        assertEquals(jobId, msg.get("job_id").asLong());
        assertEquals("core", msg.get("origin").asText());
        assertTrue(!last.contains("ladder"), "message v1 carries no ladder");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM vod_webhook_event WHERE job_id = ? AND event_type = 'transcode.requested'",
                Integer.class, jobId));
        // Idempotency-Key 重放：200，同一任务，不再写 outbox
        mvc.perform(post("/api/v1/assets/" + assetId + "/transcodes").header("X-Api-Key", "key-a").header("Idempotency-Key", "re-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.idempotent_replay").value(true));
        assertEquals(outboxBefore + 1, count("vod_outbox"));
        // 同一 key 换目标：409
        long[] other = uploaded("key-a");
        mvc.perform(post("/api/v1/assets/" + other[0] + "/transcodes").header("X-Api-Key", "key-a").header("Idempotency-Key", "re-1"))
                .andExpect(status().isConflict());
        // 跨租户、scope 不够、不存在的模板
        mvc.perform(post("/api/v1/assets/" + assetId + "/transcodes").header("X-Api-Key", "key-b")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/assets/" + assetId + "/transcodes").header("X-Api-Key", "key-a-upload")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/assets/" + assetId + "/transcodes").header("X-Api-Key", "key-a")
                .contentType("application/json").content("{\"template\":\"nope\"}")).andExpect(status().isNotFound());
    }

    @Test
    void retranscodeFailedOrCancelledAssetGoesBackToUploadedAndTenantTemplateCreatesSecondProfile() throws Exception {
        long[] ids = uploaded("key-a");
        jdbc.update("UPDATE vod_transcode_job SET status = 'FAILED', claim_version = 3 WHERE id = ?", ids[1]);
        jdbc.update("UPDATE vod_media_asset SET status = 'FAILED' WHERE id = ?", ids[0]);
        mvc.perform(post("/api/v1/assets/" + ids[0] + "/transcodes").header("X-Api-Key", "key-a")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.claim_version").value(4));
        assertEquals("UPLOADED", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, ids[0]));
        // 租户模板：新的 profile 一行任务，带 template_id；别的租户看不到这个模板
        jdbc.update("INSERT INTO vod_transcode_template(tenant_id, name, ladder_json, audio_kbps) VALUES (1, 'mobile', '[[\"360p\",360,500]]', 64)");
        JsonNode created = body(mvc.perform(post("/api/v1/assets/" + ids[0] + "/transcodes").header("X-Api-Key", "key-a")
                .contentType("application/json").content("{\"template\":\"mobile\"}")).andExpect(status().isAccepted()));
        assertEquals("mobile", created.get("profile").asText());
        assertTrue(created.get("template_id").isNumber());
        long[] b = uploaded("key-b");
        mvc.perform(post("/api/v1/assets/" + b[0] + "/transcodes").header("X-Api-Key", "key-b")
                .contentType("application/json").content("{\"template\":\"mobile\"}")).andExpect(status().isNotFound());
    }

    @Test
    void notUploadedAssetIsConflict() throws Exception {
        JsonNode opened = body(mvc.perform(post("/api/v1/uploads").header("X-Api-Key", "key-a").contentType("application/json").content("{}")));
        mvc.perform(post("/api/v1/assets/" + opened.get("asset_id").asLong() + "/transcodes").header("X-Api-Key", "key-a"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/assets/999999/transcodes").header("X-Api-Key", "key-a")).andExpect(status().isNotFound());
    }

    @Test
    void retryOnlyFailedOrCancelledOwnJobs() throws Exception {
        long[] ids = uploaded("key-a");
        long jobId = ids[1];
        for (String s : List.of("PENDING", "RUNNING", "RETRYING", "SUCCEEDED")) {
            jdbc.update("UPDATE vod_transcode_job SET status = ? WHERE id = ?", s, jobId);
            mvc.perform(post("/api/v1/jobs/" + jobId + "/retry").header("X-Api-Key", "key-a")).andExpect(status().isConflict());
        }
        jdbc.update("UPDATE vod_transcode_job SET status = 'FAILED', claim_version = 5, attempts = 3, error_code = 'NO_VIDEO_STREAM', "
                + "last_error = 'x' WHERE id = ?", jobId);
        jdbc.update("UPDATE vod_media_asset SET status = 'FAILED' WHERE id = ?", ids[0]);
        mvc.perform(post("/api/v1/jobs/" + jobId + "/retry").header("X-Api-Key", "key-b")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/" + jobId + "/retry").header("X-Api-Key", "key-a-upload")).andExpect(status().isForbidden());
        int outbox = count("vod_outbox");
        mvc.perform(post("/api/v1/jobs/" + jobId + "/retry").header("X-Api-Key", "key-a").header("Idempotency-Key", "r1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.claim_version").value(6))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.error_code").isEmpty())
                .andExpect(jsonPath("$.last_error").isEmpty());
        assertEquals("UPLOADED", jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, ids[0]));
        assertEquals(outbox + 1, count("vod_outbox"));
        mvc.perform(post("/api/v1/jobs/" + jobId + "/retry").header("X-Api-Key", "key-a").header("Idempotency-Key", "r1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotent_replay").value(true));
        assertEquals(outbox + 1, count("vod_outbox"));
        // CANCELLED 默认允许重试
        mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel").header("X-Api-Key", "key-a")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/jobs/" + jobId + "/retry").header("X-Api-Key", "key-a")).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/jobs/999999/retry").header("X-Api-Key", "key-a")).andExpect(status().isNotFound());
    }

    @Test
    void concurrentAndDailyQuotasReturn429() throws Exception {
        jdbc.update("UPDATE vod_tenant SET max_concurrent_jobs = 1 WHERE id = 1");
        long[] first = uploaded("key-a");  // 1 个 PENDING
        JsonNode opened = body(mvc.perform(post("/api/v1/uploads").header("X-Api-Key", "key-a").contentType("application/json").content("{}")));
        long assetId = opened.get("asset_id").asLong();
        storage.put("vod-source", jdbc.queryForObject("SELECT source_key FROM vod_media_asset WHERE id = ?", String.class, assetId));
        mvc.perform(post("/api/v1/uploads/" + opened.get("session_id").asLong() + "/complete").header("X-Api-Key", "key-a"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("quota_exceeded"))
                .andExpect(jsonPath("$.quota").value("concurrent_jobs"));
        assertEquals(1, count("vod_transcode_job"));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM vod_upload_session WHERE id = ?", String.class,
                opened.get("session_id").asLong()));
        // 第一个结束后可以继续
        jdbc.update("UPDATE vod_transcode_job SET status = 'SUCCEEDED' WHERE id = ?", first[1]);
        mvc.perform(post("/api/v1/uploads/" + opened.get("session_id").asLong() + "/complete").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk());
        // 每日上限：今天已经 2 次
        jdbc.update("UPDATE vod_tenant SET max_concurrent_jobs = NULL, max_daily_transcodes = 2 WHERE id = 1");
        mvc.perform(post("/api/v1/assets/" + first[0] + "/transcodes").header("X-Api-Key", "key-a"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.quota").value("daily_transcodes"));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE id = ?", String.class, first[1]));
        // 用量：配额、并发、今日次数、历史
        mvc.perform(get("/api/v1/usage?days=3").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcode_jobs").value(2))
                .andExpect(jsonPath("$.storage_bytes").value(256))
                .andExpect(jsonPath("$.storage_used_bytes").value(256))
                .andExpect(jsonPath("$.quota.max_daily_transcodes").value(2))
                .andExpect(jsonPath("$.quota.max_concurrent_jobs").isEmpty())
                .andExpect(jsonPath("$.quota.storage_bytes").isEmpty())
                .andExpect(jsonPath("$.concurrency.active_jobs").value(1))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[2].transcode_jobs").value(2))
                .andExpect(jsonPath("$.history[0].transcode_jobs").value(0));
        mvc.perform(get("/api/v1/usage?days=0").header("X-Api-Key", "key-a")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/usage").header("X-Api-Key", "key-b")).andExpect(jsonPath("$.transcode_jobs").value(0))
                .andExpect(jsonPath("$.history").doesNotExist());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
