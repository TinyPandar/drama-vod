package vod.web;

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
import vod.media.CorePlayToken;
import vod.media.HlsSigner;
import vod.security.SecretGuard;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/assets/{id}/playback-token 与 GET .../playback 共用签名逻辑。 */
@SpringBootTest(properties = {"vod.playback.token-ttl-seconds=600", "vod.playback.token-ttl-max-seconds=900"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlaybackTokenTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretGuard secrets;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_usage_daily", "vod_media_asset", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        credential(1, "key-a", "asset");
        credential(2, "key-b", "asset");
        credential(1, "key-a-upload", "upload");
    }

    @Test
    void issuesCoreTokenWithGenUrlAndNoStore() throws Exception {
        long asset = asset(1, "READY", 1, null);
        jdbc.update("UPDATE vod_media_asset SET hls_prefix = ? WHERE id = ?", "assets/v1/" + asset + "/34-2/", asset);
        long before = Instant.now().getEpochSecond();
        String body = mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", "key-a")
                        .contentType("application/json").content("{\"viewer_id\":\"v-7\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.encrypted").value(true))
                .andExpect(jsonPath("$.asset_id").value(asset))
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        long exp = r.get("expires_at").asLong();
        assertTrue(exp >= before + 600 && exp <= Instant.now().getEpochSecond() + 600);
        assertEquals(HlsSigner.v1GenMasterUrl(exp, asset, "34-2", secrets.hlsSecret()), r.get("master_url").asText());
        var claims = CorePlayToken.verify(r.get("token").asText(), secrets.playTokenSecret(), before, 0);
        assertNotNull(claims);
        assertEquals(1, claims.tenantId());
        assertEquals(asset, claims.assetId());
        assertEquals(exp, claims.exp());
        assertEquals("v-7", claims.viewerId());
        // 记一次播放用量，和 GET .../playback 一样
        assertEquals(1L, jdbc.queryForObject("SELECT play_requests FROM vod_usage_daily WHERE tenant_id = 1", Long.class));
    }

    @Test
    void fixedPrefixAssetsKeepTheOldUrlShape() throws Exception {
        long asset = asset(1, "READY", 0, null);
        jdbc.update("UPDATE vod_media_asset SET hls_prefix = ? WHERE id = ?", "assets/v1/" + asset + "/", asset);
        JsonNode token = json.readTree(mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.encrypted").value(false))
                .andReturn().getResponse().getContentAsString());
        long exp = token.get("expires_at").asLong();
        assertEquals(HlsSigner.v1MasterUrl(exp, asset, secrets.hlsSecret()), token.get("master_url").asText());

        JsonNode play = json.readTree(mvc.perform(get("/api/v1/assets/" + asset + "/playback").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        assertEquals(HlsSigner.v1MasterUrl(play.get("expires_at").asLong(), asset, secrets.hlsSecret()),
                play.get("master_url").asText());
    }

    @Test
    void getPlaybackSharesGenSigning() throws Exception {
        long asset = asset(1, "READY", 0, "assets/v1/{id}/9-1/");
        JsonNode play = json.readTree(mvc.perform(get("/api/v1/assets/" + asset + "/playback").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(HlsSigner.v1GenMasterUrl(play.get("expires_at").asLong(), asset, "9-1", secrets.hlsSecret()),
                play.get("master_url").asText());
    }

    @Test
    void crossTenantMissingScopeAndNotReady() throws Exception {
        long asset = asset(1, "READY", 0, null);
        mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", "key-b"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/assets/" + (asset + 1000) + "/playback-token").header("X-Api-Key", "key-a"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", "key-a-upload"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/assets/" + asset + "/playback-token")).andExpect(status().isUnauthorized());
        for (String s : List.of("UPLOADING", "UPLOADED", "TRANSCODING", "FAILED", "CANCELLED")) {
            long pending = asset(1, s, 0, null);
            mvc.perform(post("/api/v1/assets/" + pending + "/playback-token").header("X-Api-Key", "key-a"))
                    .andExpect(status().isConflict());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_usage_daily", Integer.class));
    }

    @Test
    void ttlIsCappedAndValidated() throws Exception {
        long asset = asset(1, "READY", 0, null);
        long before = Instant.now().getEpochSecond();
        JsonNode capped = json.readTree(mvc.perform(post("/api/v1/assets/" + asset + "/playback-token")
                        .header("X-Api-Key", "key-a").contentType("application/json").content("{\"ttl_seconds\":999999}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(capped.get("expires_at").asLong() <= Instant.now().getEpochSecond() + 900);
        assertTrue(capped.get("expires_at").asLong() >= before + 900);
        JsonNode shortTtl = json.readTree(mvc.perform(post("/api/v1/assets/" + asset + "/playback-token")
                        .header("X-Api-Key", "key-a").contentType("application/json").content("{\"ttl_seconds\":5}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(shortTtl.get("expires_at").asLong() <= Instant.now().getEpochSecond() + 5);
        for (String bad : List.of("{\"ttl_seconds\":0}", "{\"ttl_seconds\":-1}", "{\"ttl_seconds\":\"60\"}",
                "{\"ttl_seconds\":1.5}", "{\"viewer_id\":\"has space\"}", "{\"viewer_id\":7}")) {
            mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", "key-a")
                    .contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        }
    }

    private void credential(long tenantId, String raw, String scopes) {
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (?,?,?)",
                tenantId, ApiKeys.sha256(raw), scopes);
    }

    private long asset(long tenantId, String status, int encrypted, String prefixTemplate) {
        jdbc.update("INSERT INTO vod_media_asset(tenant_id, status, source_bucket, source_key, encrypted) VALUES (?,?,?,?,?)",
                tenantId, status, "vod-source", "raw/x/source.mp4", encrypted);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM vod_media_asset", Long.class);
        if (prefixTemplate != null) {
            jdbc.update("UPDATE vod_media_asset SET hls_prefix = ? WHERE id = ?", prefixTemplate.replace("{id}", Long.toString(id)), id);
        }
        return id;
    }
}
