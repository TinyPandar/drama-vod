package vod.webhook;

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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 5 第 3、4 项：事件进独立表、异步投递、重试与最终失败、停用、SSRF 与 DNS 重绑定。 */
@SpringBootTest(properties = {"vod.webhook.max-attempts=3", "vod.webhook.base-backoff-seconds=1",
        "vod.webhook.max-backoff-seconds=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookDeliveryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeWebhookIo io;
    @Autowired WebhookDispatcher dispatcher;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_webhook_delivery", "vod_webhook_event", "vod_webhook_endpoint", "vod_output_prefix", "vod_outbox",
                "vod_transcode_job", "vod_media_asset", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, '*')", ApiKeys.sha256("key-a"));
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (2, ?, '*')", ApiKeys.sha256("key-b"));
        jdbc.update("INSERT INTO vod_media_asset(id, tenant_id, status, source_bucket, source_key) VALUES (5, 1, 'TRANSCODING', 'b', 'k')");
        jdbc.update("INSERT INTO vod_transcode_job(id, tenant_id, asset_id, profile, status, claim_version) VALUES (9, 1, 5, 'ladder_v1', 'RUNNING', 2)");
        io.reset();
    }

    JsonNode register(String key, String url, String events) throws Exception {
        String content = events == null ? "{\"url\":\"" + url + "\"}" : "{\"url\":\"" + url + "\",\"events\":" + events + "}";
        return json.readTree(mvc.perform(post("/api/v1/webhooks").header("X-Api-Key", key).contentType("application/json").content(content))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    void event(String type) {
        jdbc.update("INSERT INTO vod_webhook_event(tenant_id, event_type, job_id, asset_id, claim_version) VALUES (1, ?, 9, 5, 2)", type);
    }

    @Test
    void cancelRecordsEventInSameTransactionAndDispatcherSignsAndDelivers() throws Exception {
        JsonNode hook = register("key-a", "https://hooks.example.com/vod", null);
        String secret = hook.get("secret").asText();
        assertEquals("transcode.succeeded,transcode.failed,transcode.cancelled", hook.get("events").asText());
        register("key-b", "https://other.example.com/vod", "[\"*\"]");  // 别的租户收不到

        mvc.perform(post("/api/v1/jobs/9/cancel").header("X-Api-Key", "key-a")).andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_webhook_event WHERE event_type = 'transcode.cancelled' AND job_id = 9", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_outbox", Integer.class), "webhook events never go to vod_outbox");
        assertTrue(io.posts.isEmpty(), "no HTTP inside the request");
        mvc.perform(post("/api/v1/jobs/9/cancel").header("X-Api-Key", "key-a")).andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vod_webhook_event", Integer.class), "idempotent cancel adds no event");

        assertEquals(1, dispatcher.runOnce());
        assertEquals(1, io.posts.size());
        var p = io.posts.get(0);
        assertEquals("hooks.example.com", p.host());
        JsonNode payload = json.readTree(p.body());
        assertEquals("transcode.cancelled", payload.get("event_type").asText());
        assertEquals(9, payload.get("job_id").asLong());
        assertEquals(5, payload.get("asset_id").asLong());
        assertEquals("cancelled", payload.get("reason").asText());
        assertEquals(3, payload.get("claim_version").asInt());
        assertNotNull(payload.get("occurred_at").asText());
        assertEquals(WebhookSigner.sha256(secret, p.body()), p.headers().get("X-Vod-Signature"));
        assertEquals("transcode.cancelled", p.headers().get("X-Vod-Event"));
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM vod_webhook_delivery", String.class));
        assertEquals(0, dispatcher.runOnce(), "delivered once only");

        String rows = mvc.perform(get("/api/v1/webhook-deliveries").header("X-Api-Key", "key-a"))
                .andExpect(jsonPath("$.deliveries[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveries[0].job_id").value(9))
                .andExpect(jsonPath("$.deliveries[0].response_code").value(204))
                .andReturn().getResponse().getContentAsString();
        assertFalse(rows.contains(secret));
        mvc.perform(get("/api/v1/webhook-deliveries").header("X-Api-Key", "key-b")).andExpect(jsonPath("$.deliveries").isEmpty());
    }

    @Test
    void failedDeliveryBacksOffThenFailsAfterMaxAttemptsWithSameBody() throws Exception {
        register("key-a", "https://hooks.example.com/vod", null);
        io.responder = p -> 500;
        event(WebhookService.FAILED);
        dispatcher.runOnce();
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM vod_webhook_delivery", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM vod_webhook_delivery", Integer.class));
        assertEquals(500, jdbc.queryForObject("SELECT response_code FROM vod_webhook_delivery", Integer.class));
        dispatcher.runOnce();
        assertEquals(1, io.posts.size(), "not due yet (backoff)");
        for (int i = 0; i < 2; i++) {
            jdbc.update("UPDATE vod_webhook_delivery SET next_attempt_at = NULL");
            dispatcher.runOnce();
        }
        assertEquals(3, io.posts.size());
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM vod_webhook_delivery", String.class));
        assertEquals(io.posts.get(0).body(), io.posts.get(2).body(), "retries sign the same bytes");
        assertEquals("3", io.posts.get(2).headers().get("X-Vod-Delivery-Attempt"));
        jdbc.update("UPDATE vod_webhook_delivery SET next_attempt_at = NULL");
        dispatcher.runOnce();
        assertEquals(3, io.posts.size(), "final FAILED is not retried");
    }

    @Test
    void disabledEndpointIsSkippedAndUnsubscribedEventIsIgnored() throws Exception {
        long id = register("key-a", "https://hooks.example.com/vod", "\"transcode.succeeded\"").get("id").asLong();
        event(WebhookService.FAILED);
        dispatcher.runOnce();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_webhook_delivery", Integer.class));
        event(WebhookService.SUCCEEDED);
        dispatcher.fanOut();
        mvc.perform(post("/api/v1/webhooks/" + id + "/disable").header("X-Api-Key", "key-b")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/webhooks/" + id + "/disable").header("X-Api-Key", "key-a"))
                .andExpect(jsonPath("$.status").value("DISABLED")).andExpect(jsonPath("$.secret").doesNotExist());
        dispatcher.runOnce();
        assertTrue(io.posts.isEmpty());
        assertEquals("SKIPPED", jdbc.queryForObject("SELECT status FROM vod_webhook_delivery", String.class));
        mvc.perform(post("/api/v1/webhooks/" + id + "/enable").header("X-Api-Key", "key-a")).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void dnsRebindingToLoopbackAfterRegistrationNeverConnects() throws Exception {
        // 登记时解析到公网，之后 DNS 被改成 127.0.0.1
        io.lookups = new ArrayList<>(List.of(List.of(FakeWebhookIo.addr("93.184.215.14")), List.of(InetAddress.getLoopbackAddress())));
        register("key-a", "https://rebind.example.com/hook", null);
        event(WebhookService.SUCCEEDED);
        dispatcher.runOnce();
        assertTrue(io.posts.isEmpty(), "must not connect anywhere");
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM vod_webhook_delivery", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT last_error FROM vod_webhook_delivery", String.class).startsWith("blocked"));
    }

    @Test
    void deliveryConnectsToTheAddressItJustValidated() throws Exception {
        register("key-a", "https://pin.example.com/hook", null);
        // 投递时解析出公网地址 A；之后再查 DNS 会变成内网 —— 连接必须用 A，且每次投递只解析一次
        io.lookups = new ArrayList<>(List.of(List.of(FakeWebhookIo.addr("93.184.215.15")), List.of(FakeWebhookIo.addr("10.0.0.5"))));
        io.lookedUp.clear();
        event(WebhookService.SUCCEEDED);
        dispatcher.runOnce();
        assertEquals(1, io.posts.size());
        assertEquals("93.184.215.15", io.posts.get(0).address().getHostAddress());
        assertEquals(1, io.lookedUp.size());
        // 一个公网 + 一个内网混在一起：整体拒绝
        io.lookups = new ArrayList<>(List.of(List.of(FakeWebhookIo.addr("93.184.215.15"), FakeWebhookIo.addr("::ffff:192.168.1.1"))));
        mvc.perform(post("/api/v1/webhooks").header("X-Api-Key", "key-a").contentType("application/json")
                .content("{\"url\":\"https://mixed.example.com/h\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void registrationRejectsSsrfTargetsAndUnknownEvents() throws Exception {
        for (String url : List.of("http://hooks.example.com/x", "https://127.0.0.1/x", "https://[::1]/x", "https://[fd00::1]/x",
                "https://[fe80::1]/x", "https://[::ffff:10.0.0.1]/x", "https://169.254.169.254/latest", "https://localhost/x",
                "https://metadata.google.internal/x", "https://user:pw@hooks.example.com/x")) {
            mvc.perform(post("/api/v1/webhooks").header("X-Api-Key", "key-a").contentType("application/json")
                    .content("{\"url\":\"" + url + "\"}")).andExpect(status().isBadRequest());
        }
        io.lookups = new ArrayList<>(List.of(List.of(FakeWebhookIo.addr("fc00::5"))));
        mvc.perform(post("/api/v1/webhooks").header("X-Api-Key", "key-a").contentType("application/json")
                .content("{\"url\":\"https://ula.example.com/x\"}")).andExpect(status().isBadRequest());
        io.reset();
        mvc.perform(post("/api/v1/webhooks").header("X-Api-Key", "key-a").contentType("application/json")
                .content("{\"url\":\"https://hooks.example.com/x\",\"events\":[\"transcode.exploded\"]}")).andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_webhook_endpoint", Integer.class));
    }
}
