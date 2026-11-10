package vod.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import vod.auth.ApiKeys;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 3 遗留：取消撞上死锁/锁超时在事务外重试一次，仍失败 503；取消把 WRITING 前缀置 GARBAGE。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobCancelRetryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean JobService jobService;

    @BeforeEach
    void seed() {
        reset(jobService);
        for (String t : List.of("vod_output_prefix", "vod_transcode_job", "vod_media_asset", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, 'job')", ApiKeys.sha256("key-a"));
        jdbc.update("INSERT INTO vod_media_asset(id, tenant_id, status, source_bucket, source_key) VALUES (5, 1, 'TRANSCODING', 'b', 'k')");
        jdbc.update("INSERT INTO vod_transcode_job(id, tenant_id, asset_id, profile, status, claim_version) VALUES (9, 1, 5, 'ladder_v1', 'RUNNING', 2)");
    }

    @Test
    void deadlockOnceIsRetriedAndSucceeds() throws Exception {
        doThrow(new DeadlockLoserDataAccessException("deadlock", null)).doCallRealMethod()
                .when(jobService).cancel(anyLong(), anyLong());
        mvc.perform(post("/api/v1/jobs/9/cancel").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.claim_version").value(3));
        verify(jobService, times(2)).cancel(1L, 9L);
    }

    @Test
    void lockTimeoutTwiceIs503AndChangesNothing() throws Exception {
        doThrow(new CannotAcquireLockException("lock wait timeout")).when(jobService).cancel(anyLong(), anyLong());
        mvc.perform(post("/api/v1/jobs/9/cancel").header("X-Api-Key", "key-a"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
        verify(jobService, times(2)).cancel(1L, 9L);
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM vod_transcode_job WHERE id = 9", String.class));
    }

    @Test
    void otherErrorsAreNotRetried() throws Exception {
        mvc.perform(post("/api/v1/jobs/404/cancel").header("X-Api-Key", "key-a")).andExpect(status().isNotFound());
        verify(jobService, times(1)).cancel(1L, 404L);
    }

    @Test
    void cancelMarksWritingPrefixesGarbageButKeepsActive() throws Exception {
        doCallRealMethod().when(jobService).cancel(anyLong(), anyLong());
        jdbc.update("INSERT INTO vod_output_prefix(asset_id, job_id, claim_version, prefix, status) VALUES "
                + "(5, 9, 2, 'assets/v1/5/9-2/', 'WRITING'), (5, 9, 1, 'assets/v1/5/9-1/', 'GARBAGE'), "
                + "(5, 8, 1, 'assets/v1/5/8-1/', 'ACTIVE'), (6, 10, 1, 'assets/v1/6/10-1/', 'WRITING')");
        mvc.perform(post("/api/v1/jobs/9/cancel").header("X-Api-Key", "key-a")).andExpect(status().isOk());
        assertEquals("GARBAGE", prefix("assets/v1/5/9-2/"));
        assertEquals("GARBAGE", prefix("assets/v1/5/9-1/"));
        assertEquals("ACTIVE", prefix("assets/v1/5/8-1/"));
        assertEquals("WRITING", prefix("assets/v1/6/10-1/"));  // 别的任务不碰
    }

    private String prefix(String p) {
        return jdbc.queryForObject("SELECT status FROM vod_output_prefix WHERE prefix = ?", String.class, p);
    }
}
