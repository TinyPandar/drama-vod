package vod.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vod.auth.ApiKeys;
import vod.security.SecretGuard;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 5 第 1、2 项：管理端 JWT 与租户 API Key 隔离；租户与 key 管理。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiTest {
    static final String PASSWORD = "test-only-admin-password";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired SecretGuard secrets;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_admin_user", "vod_api_credential", "vod_tenant", "vod_upload_session", "vod_media_asset",
                "vod_storage_object")) {
            jdbc.update("DELETE FROM " + t);
        }
        AdminUserSeeder.seed(jdbc, encoder, "ops", PASSWORD);
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, '*')", ApiKeys.sha256("key-a"));
    }

    String login() throws Exception {
        String body = mvc.perform(post("/api/v1/admin/login").contentType("application/json")
                        .content("{\"username\":\"ops\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("token").asText();
        assertTrue(token.startsWith("vadm1."));
        return token;
    }

    @Test
    void passwordIsStoredHashedAndBadLoginIs401() throws Exception {
        String hash = jdbc.queryForObject("SELECT password_hash FROM vod_admin_user WHERE username = 'ops'", String.class);
        assertFalse(hash.contains(PASSWORD));
        assertTrue(hash.startsWith("$2"));
        mvc.perform(post("/api/v1/admin/login").contentType("application/json")
                .content("{\"username\":\"ops\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/login").contentType("application/json")
                .content("{\"username\":\"nobody\",\"password\":\"x\"}")).andExpect(status().isUnauthorized());
        login();
    }

    @Test
    void adminAndTenantCredentialsAreIsolated() throws Exception {
        String token = login();
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        // 没带凭证、租户 Key（两种头）调管理接口：401
        mvc.perform(get("/api/v1/admin/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/tenants").header("X-Api-Key", "key-a")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer key-a")).andExpect(status().isUnauthorized());
        // 管理 JWT 调租户上传、资产接口：401
        mvc.perform(post("/api/v1/uploads").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/assets").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", token)).andExpect(status().isUnauthorized());
        // 放行路径不受影响
        mvc.perform(get("/api/v1/healthz")).andExpect(status().isOk());
    }

    @Test
    void forgedExpiredAndAlgNoneTokensAreRejected() throws Exception {
        long now = Instant.now().getEpochSecond();
        String expired = AdminJwt.issue(new AdminJwt.Claims(id("ops"), "ops", now - 7200, now - 3600), secrets.adminJwtSecret());
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        String forged = AdminJwt.issue(new AdminJwt.Claims(id("ops"), "ops", now, now + 600),
                "another-secret-another-secret-xx".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"kid\":\"v1\"}".getBytes());
        String claims = forged.split("\\.")[2];
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer vadm1." + b64 + "." + claims + "."))
                .andExpect(status().isUnauthorized());
        // 管理用户停用后旧 token 立即失效
        String token = login();
        jdbc.update("UPDATE vod_admin_user SET status = 'DISABLED'");
        mvc.perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tenantKeyLifecycle() throws Exception {
        String token = "Bearer " + login();
        JsonNode tenant = body(mvc.perform(post("/api/v1/admin/tenants").header("Authorization", token)
                .contentType("application/json").content("{\"name\":\"short-video\",\"max_concurrent_jobs\":2}"))
                .andExpect(status().isCreated()));
        long tenantId = tenant.get("id").asLong();
        assertEquals(2, tenant.get("max_concurrent_jobs").asInt());
        mvc.perform(post("/api/v1/admin/tenants").header("Authorization", token)
                .contentType("application/json").content("{\"name\":\"short-video\"}")).andExpect(status().isConflict());

        JsonNode key = body(mvc.perform(post("/api/v1/admin/tenants/" + tenantId + "/keys").header("Authorization", token)
                .contentType("application/json").content("{\"name\":\"ci\",\"scopes\":[\"upload\",\"asset\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))));
        String plaintext = key.get("api_key").asText();
        long keyId = key.get("id").asLong();
        assertTrue(plaintext.startsWith("vk_"));
        assertEquals(ApiKeys.sha256(plaintext),
                jdbc.queryForObject("SELECT key_hash FROM vod_api_credential WHERE id = ?", String.class, keyId));
        String list = mvc.perform(get("/api/v1/admin/tenants/" + tenantId + "/keys").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(plaintext));
        assertFalse(list.contains(ApiKeys.sha256(plaintext)));
        assertFalse(list.contains("api_key"));

        mvc.perform(get("/api/v1/assets").header("X-Api-Key", plaintext)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/jobs").header("X-Api-Key", plaintext)).andExpect(status().isForbidden());  // 没有 job scope

        // 停用租户 → key 失效；重新启用 → 恢复
        mvc.perform(patch("/api/v1/admin/tenants/" + tenantId).header("Authorization", token)
                .contentType("application/json").content("{\"status\":\"DISABLED\"}")).andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", plaintext)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/tenants/" + tenantId).header("Authorization", token)
                .contentType("application/json").content("{\"status\":\"ACTIVE\",\"storage_quota_bytes\":1000,\"max_daily_transcodes\":null}"))
                .andExpect(jsonPath("$.storage_quota_bytes").value(1000))
                .andExpect(jsonPath("$.max_daily_transcodes").isEmpty());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", plaintext)).andExpect(status().isOk());

        // 轮换（无宽限）：旧 key 立即失效，新 key 可用，明文只在响应里
        JsonNode rotated = body(mvc.perform(post("/api/v1/admin/keys/" + keyId + "/rotate").header("Authorization", token))
                .andExpect(status().isCreated()));
        String next = rotated.get("api_key").asText();
        assertEquals("REVOKED", rotated.get("previous_key").get("status").asText());
        assertEquals("upload,asset", rotated.get("scopes").asText());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", plaintext)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", next)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/keys/" + keyId + "/rotate").header("Authorization", token)).andExpect(status().isConflict());

        // 轮换（有宽限）：旧 key 在宽限期内仍可用
        long nextId = rotated.get("id").asLong();
        JsonNode graced = body(mvc.perform(post("/api/v1/admin/keys/" + nextId + "/rotate").header("Authorization", token)
                .contentType("application/json").content("{\"grace_seconds\":600}")).andExpect(status().isCreated()));
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", next)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", graced.get("api_key").asText())).andExpect(status().isOk());

        // 撤销
        mvc.perform(post("/api/v1/admin/keys/" + graced.get("id").asLong() + "/revoke").header("Authorization", token))
                .andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", graced.get("api_key").asText())).andExpect(status().isUnauthorized());

        // 过期
        JsonNode shortLived = body(mvc.perform(post("/api/v1/admin/tenants/" + tenantId + "/keys").header("Authorization", token)
                .contentType("application/json").content("{\"ttl_seconds\":60}")).andExpect(status().isCreated()));
        assertTrue(shortLived.get("expires_at").isTextual());
        jdbc.update("UPDATE vod_api_credential SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), shortLived.get("id").asLong());
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", shortLived.get("api_key").asText())).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/admin/tenants/" + tenantId + "/keys").header("Authorization", token)
                .contentType("application/json").content("{\"scopes\":[\"admin\"]}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/tenants/999999/keys").header("Authorization", token)).andExpect(status().isNotFound());
    }

    @Test
    void devSeededKeyStillWorks() throws Exception {
        // DevCredentialSeeder 的行为（哈希入库、* scope）没有改：直接按它的写法插入后可用。
        mvc.perform(get("/api/v1/assets").header("X-Api-Key", "key-a")).andExpect(status().isOk());
        assertNull(jdbc.queryForObject("SELECT key_prefix FROM vod_api_credential WHERE tenant_id = 1", String.class));
    }

    private long id(String username) {
        return jdbc.queryForObject("SELECT id FROM vod_admin_user WHERE username = ?", Long.class, username);
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
