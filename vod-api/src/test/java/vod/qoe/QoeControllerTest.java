package vod.qoe;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vod.media.CorePlayToken;
import vod.security.SecretGuard;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/qoe：不要 API Key，凭 body 里的 Core token；指标出现在 /actuator/prometheus。 */
@SpringBootTest(properties = {"vod.qoe.token-grace-seconds=120", "vod.qoe.max-body-bytes=2048", "vod.qoe.max-events=5"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class QoeControllerTest {
    @Autowired MockMvc mvc;
    @Autowired SecretGuard secrets;

    @Test
    void validReportRecordsMetricsWithoutAssetOrSessionLabels() throws Exception {
        String before = prometheus();
        double startups = value(before, "vod_qoe_startup_seconds_count", null);
        double rebuffers = value(before, "vod_qoe_rebuffer_total", null);
        double stallSum = value(before, "vod_qoe_rebuffer_seconds_sum", null);
        double fragErrors = value(before, "vod_qoe_playback_errors_total", "code=\"fragLoadError\"");
        double other = value(before, "vod_qoe_playback_errors_total", "code=\"other\"");

        String body = "{\"token\":\"" + token(60) + "\",\"session_id\":\"s-123456\",\"events\":["
                + "{\"type\":\"startup\",\"ms\":850},{\"type\":\"rebuffer\",\"ms\":1500},"
                + "{\"type\":\"error\",\"code\":\"fragLoadError\"},{\"type\":\"error\",\"code\":\"<script>\"},"
                + "{\"type\":\"ended\"}]}";
        mvc.perform(post("/api/v1/qoe").contentType("text/plain").content(body))   // sendBeacon 常见 text/plain
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        String after = prometheus();
        assertEquals(startups + 1, value(after, "vod_qoe_startup_seconds_count", null));
        assertTrue(after.contains("vod_qoe_startup_seconds_bucket"), "startup is a histogram");
        assertEquals(rebuffers + 1, value(after, "vod_qoe_rebuffer_total", null));
        assertEquals(stallSum + 1.5, value(after, "vod_qoe_rebuffer_seconds_sum", null), 1e-9);
        assertEquals(fragErrors + 1, value(after, "vod_qoe_playback_errors_total", "code=\"fragLoadError\""));
        assertEquals(other + 1, value(after, "vod_qoe_playback_errors_total", "code=\"other\""));
        assertFalse(after.contains("<script>"));
        for (String line : after.split("\n")) {
            if (line.startsWith("vod_qoe_")) {
                assertFalse(line.contains("asset") || line.contains("session") || line.contains("tenant"), line);
            }
        }
    }

    @Test
    void invalidOrLongExpiredTokenIsUnauthorized() throws Exception {
        String events = ",\"session_id\":\"s-1\",\"events\":[{\"type\":\"startup\",\"ms\":1}]}";
        mvc.perform(post("/api/v1/qoe").contentType("application/json").content("{\"token\":\"nope\"" + events))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/qoe").contentType("application/json").content("{\"session_id\":\"s-1\",\"events\":[]}"))
                .andExpect(status().isUnauthorized());
        // 过期但在宽限期内：接受；超过宽限期：401
        mvc.perform(post("/api/v1/qoe").contentType("application/json").content("{\"token\":\"" + token(-60) + "\"" + events))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/qoe").contentType("application/json").content("{\"token\":\"" + token(-600) + "\"" + events))
                .andExpect(status().isUnauthorized());
        // API Key 不能代替 token
        mvc.perform(post("/api/v1/qoe").header("X-Api-Key", "anything").contentType("application/json")
                .content("{\"session_id\":\"s-1\",\"events\":[]}")).andExpect(status().isUnauthorized());
    }

    @Test
    void oversizedBodyAndTooManyEventsAre413() throws Exception {
        String big = "{\"token\":\"" + token(60) + "\",\"session_id\":\"s-1\",\"pad\":\"" + "x".repeat(4096) + "\",\"events\":[]}";
        mvc.perform(post("/api/v1/qoe").contentType("application/json").content(big))
                .andExpect(status().isPayloadTooLarge());
        StringBuilder many = new StringBuilder("{\"token\":\"" + token(60) + "\",\"session_id\":\"s-1\",\"events\":[");
        for (int i = 0; i < 6; i++) many.append(i == 0 ? "" : ",").append("{\"type\":\"ended\"}");
        mvc.perform(post("/api/v1/qoe").contentType("application/json").content(many + "]}"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void malformedBodiesAre400() throws Exception {
        mvc.perform(post("/api/v1/qoe").contentType("application/json").content("not json")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/qoe").contentType("application/json").content("[]")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/qoe").contentType("application/json")
                .content("{\"token\":\"" + token(60) + "\",\"session_id\":\"bad id!\",\"events\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/qoe").contentType("application/json")
                .content("{\"token\":\"" + token(60) + "\",\"session_id\":\"s-1\",\"events\":{}}")).andExpect(status().isBadRequest());
    }

    private String token(long ttl) {
        return CorePlayToken.issue(new CorePlayToken.Claims(1, 42, Instant.now().getEpochSecond() + ttl, null),
                secrets.playTokenSecret());
    }

    private String prometheus() throws Exception {
        return mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** 没有该序列时按 0。label 为 null 时取第一条。 */
    private static double value(String body, String name, String label) {
        Matcher m = Pattern.compile("^" + Pattern.quote(name) + "(\\{[^}]*\\})?\\s+(\\S+)$", Pattern.MULTILINE).matcher(body);
        while (m.find()) {
            if (label == null || (m.group(1) != null && m.group(1).contains(label))) return Double.parseDouble(m.group(2));
        }
        return 0;
    }
}
