package vod.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /actuator/prometheus 能看到任务积压与 outbox gauge。 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class JobMetricsTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobMetrics metrics;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM vod_outbox");
        jdbc.update("DELETE FROM vod_transcode_job");
    }

    @Test
    void backlogAndOutboxGaugesAreExported() throws Exception {
        Instant now = Instant.now();
        job("PENDING", now.minusSeconds(120));
        job("PENDING", now.minusSeconds(10));
        job("RETRYING", now.minusSeconds(30));
        job("RUNNING", now);
        job("SUCCEEDED", now.minusSeconds(9999));
        job("CANCELLED", now.minusSeconds(9999));
        jdbc.update("INSERT INTO vod_outbox(tenant_id, event_type, payload, status, created_at) VALUES "
                + "(1, 'transcode.requested', '{}', 'PENDING', ?), (1, 'transcode.requested', '{}', 'PUBLISHED', ?)",
                Timestamp.from(now.minusSeconds(45)), Timestamp.from(now.minusSeconds(9999)));
        metrics.refresh();

        String body = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(2.0, value(body, "vod_jobs_backlog{status=\"PENDING\"}"));
        assertEquals(1.0, value(body, "vod_jobs_backlog{status=\"RETRYING\"}"));
        assertEquals(1.0, value(body, "vod_jobs_backlog{status=\"RUNNING\"}"));
        assertEquals(1.0, value(body, "vod_outbox_pending"));
        double pendingAge = value(body, "vod_jobs_oldest_age_seconds{status=\"PENDING\"}");
        assertTrue(pendingAge >= 119 && pendingAge < 200, String.valueOf(pendingAge));
        double retryAge = value(body, "vod_jobs_oldest_age_seconds{status=\"RETRYING\"}");
        assertTrue(retryAge >= 29 && retryAge < 100, String.valueOf(retryAge));
        double outboxAge = value(body, "vod_outbox_oldest_pending_age_seconds");
        assertTrue(outboxAge >= 44 && outboxAge < 120, String.valueOf(outboxAge));

        // 积压清空后归零
        jdbc.update("UPDATE vod_transcode_job SET status = 'SUCCEEDED'");
        jdbc.update("UPDATE vod_outbox SET status = 'PUBLISHED'");
        metrics.refresh();
        body = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        for (String name : List.of("vod_jobs_backlog{status=\"PENDING\"}", "vod_jobs_oldest_age_seconds{status=\"PENDING\"}",
                "vod_outbox_pending", "vod_outbox_oldest_pending_age_seconds")) {
            assertEquals(0.0, value(body, name), name);
        }
    }

    private void job(String status, Instant updatedAt) {
        long asset = jdbc.queryForObject("SELECT COALESCE(MAX(asset_id), 0) + 1 FROM vod_transcode_job", Long.class);
        jdbc.update("INSERT INTO vod_transcode_job(tenant_id, asset_id, profile, status, updated_at) VALUES (1, ?, 'ladder_v1', ?, ?)",
                asset, status, Timestamp.from(updatedAt));
    }

    private static double value(String body, String series) {
        // Prometheus 文本里标签可能多于测试写的那一个（如 application），这里只按指标名和 status 匹配。
        String name = series.contains("{") ? series.substring(0, series.indexOf('{')) : series;
        String label = series.contains("{") ? series.substring(series.indexOf('{') + 1, series.length() - 1) : null;
        Pattern line = Pattern.compile("^" + Pattern.quote(name) + "(\\{[^}]*\\})?\\s+(\\S+)$", Pattern.MULTILINE);
        Matcher m = line.matcher(body);
        while (m.find()) {
            if (label == null || (m.group(1) != null && m.group(1).contains(label))) return Double.parseDouble(m.group(2));
        }
        throw new AssertionError(series + " not found in:\n" + body);
    }
}
