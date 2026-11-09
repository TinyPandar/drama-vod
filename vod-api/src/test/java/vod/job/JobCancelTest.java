package vod.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vod.auth.ApiKeys;
import vod.upload.UploadService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/jobs/{id}/cancel：状态转换、claim_version 围栏、409、幂等、跨租户 404、scope 403。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobCancelTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        for (String table : List.of("vod_outbox", "vod_transcode_job", "vod_upload_session", "vod_media_asset",
                "vod_storage_object", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        credential(1, "key-a", "job");
        credential(2, "key-b", "job");
        credential(1, "key-a-asset", "asset");
    }

    @Test
    void cancellableStatesBecomeCancelledAndBumpClaimVersion() throws Exception {
        for (String from : List.of("PENDING", "RETRYING", "RUNNING")) {
            long assetId = asset(1, "RUNNING".equals(from) ? "TRANSCODING" : "UPLOADED");
            long jobId = job(1, assetId, from, 3);
            jdbc.update("UPDATE vod_transcode_job SET locked_until = ?, next_attempt_at = ?, "
                            + "updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().plusSeconds(600)), Timestamp.from(Instant.now().plusSeconds(30)),
                    Timestamp.from(Instant.now().minusSeconds(3600)), jobId);

            mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel").header("X-Api-Key", "key-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(jobId))
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.claim_version").value(4))
                    .andExpect(jsonPath("$.locked_until").isEmpty())
                    .andExpect(jsonPath("$.next_attempt_at").isEmpty());
            assertEquals("CANCELLED", assetStatus(assetId), from);
            Timestamp updated = jdbc.queryForObject("SELECT updated_at FROM vod_transcode_job WHERE id = ?",
                    Timestamp.class, jobId);
            assertEquals(true, updated.toInstant().isAfter(Instant.now().minusSeconds(60)), from);
        }
    }

    @Test
    void readyAssetIsNotDowngraded() throws Exception {
        long assetId = asset(1, "READY");
        long jobId = job(1, assetId, "RUNNING", 1);
        mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk());
        assertEquals("READY", assetStatus(assetId));
    }

    @Test
    void terminalJobsConflictAndStayUnchanged() throws Exception {
        for (String terminal : List.of("SUCCEEDED", "FAILED")) {
            long assetId = asset(1, "SUCCEEDED".equals(terminal) ? "READY" : "FAILED");
            long jobId = job(1, assetId, terminal, 2);
            mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel").header("X-Api-Key", "key-a"))
                    .andExpect(status().isConflict());
            Map<String, Object> row = jdbc.queryForMap("SELECT status, claim_version FROM vod_transcode_job WHERE id = ?", jobId);
            assertEquals(terminal, row.get("status"));
            assertEquals(2, ((Number) row.get("claim_version")).intValue());
        }
    }

    @Test
    void cancelIsIdempotent() throws Exception {
        long assetId = asset(1, "UPLOADED");
        long jobId = job(1, assetId, "PENDING", 0);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel").header("X-Api-Key", "key-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.claim_version").value(1));  // 第二次不再 +1
        }
    }

    @Test
    void otherTenantAndMissingJobAreNotFound() throws Exception {
        long assetId = asset(1, "UPLOADED");
        long jobId = job(1, assetId, "PENDING", 0);
        mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel").header("X-Api-Key", "key-b"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/" + (jobId + 1000) + "/cancel").header("X-Api-Key", "key-a"))
                .andExpect(status().isNotFound());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE id = ?", String.class, jobId));
        assertEquals("UPLOADED", assetStatus(assetId));
    }

    @Test
    void jobScopeIsRequired() throws Exception {
        long jobId = job(1, asset(1, "UPLOADED"), "PENDING", 0);
        mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel").header("X-Api-Key", "key-a-asset"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/jobs/" + jobId + "/cancel"))
                .andExpect(status().isUnauthorized());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE id = ?", String.class, jobId));
    }

    @Test
    void jobDetailReturnsErrorCodeAndNextAttempt() throws Exception {
        long jobId = job(1, asset(1, "TRANSCODING"), "RETRYING", 2);
        Instant next = Instant.parse("2030-01-01T00:00:00Z");
        jdbc.update("UPDATE vod_transcode_job SET error_code = 'STORAGE_ERROR', last_error = 'connect timeout', "
                + "next_attempt_at = ? WHERE id = ?", Timestamp.from(next), jobId);
        mvc.perform(get("/api/v1/jobs/" + jobId).header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETRYING"))
                .andExpect(jsonPath("$.error_code").value("STORAGE_ERROR"))
                .andExpect(jsonPath("$.last_error").value("connect timeout"))
                .andExpect(jsonPath("$.next_attempt_at").value(next.toString()));
        long fresh = job(1, asset(1, "UPLOADED"), "PENDING", 0);
        mvc.perform(get("/api/v1/jobs/" + fresh).header("X-Api-Key", "key-a"))
                .andExpect(jsonPath("$.error_code").isEmpty());
        assertNull(jdbc.queryForObject("SELECT error_code FROM vod_transcode_job WHERE id = ?", String.class, fresh));
    }

    private void credential(long tenantId, String raw, String scopes) {
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (?,?,?)",
                tenantId, ApiKeys.sha256(raw), scopes);
    }

    private long asset(long tenantId, String status) {
        return insert("INSERT INTO vod_media_asset(tenant_id, status, source_bucket, source_key) VALUES (?,?,?,?)",
                tenantId, status, "vod-source", "raw/x/source.mp4");
    }

    private long job(long tenantId, long assetId, String status, int claimVersion) {
        long id = insert("INSERT INTO vod_transcode_job(tenant_id, asset_id, profile, status, claim_version) VALUES (?,?,?,?,?)",
                tenantId, assetId, "ladder_v1", status, claimVersion);
        return id;
    }

    private String assetStatus(long assetId) {
        return jdbc.queryForObject("SELECT status FROM vod_media_asset WHERE id = ?", String.class, assetId);
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return UploadService.generatedId(keys);
    }
}
