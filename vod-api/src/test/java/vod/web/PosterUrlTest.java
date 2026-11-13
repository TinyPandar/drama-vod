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
import vod.media.HlsSigner;
import vod.security.SecretGuard;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 6：资产列表 / 详情 / playback-token / playback 的 poster_url。 */
@SpringBootTest(properties = {"vod.play-ttl-seconds=600", "vod.playback.token-ttl-max-seconds=900"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PosterUrlTest {
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
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (?,?,?), (?,?,?)",
                1, ApiKeys.sha256("key-a"), "asset", 2, ApiKeys.sha256("key-b"), "asset");
    }

    @Test
    void posterUrlUsesCurrentGenPrefixSignature() throws Exception {
        long asset = asset("READY");
        String prefix = "assets/v1/" + asset + "/34-2/";
        jdbc.update("UPDATE vod_media_asset SET hls_prefix = ?, poster_key = ? WHERE id = ?", prefix, prefix + "poster.jpg", asset);
        long before = Instant.now().getEpochSecond();

        JsonNode detail = getJson("/api/v1/assets/" + asset, "key-a");
        assertPoster(detail.get("poster_url").asText(), asset, "34-2", before);

        JsonNode list = getJson("/api/v1/assets", "key-a");
        assertPoster(list.get("assets").get(0).get("poster_url").asText(), asset, "34-2", before);

        JsonNode token = json.readTree(mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long exp = token.get("expires_at").asLong();
        assertEquals(HlsSigner.v1GenFileUrl(exp, asset, "34-2", "poster.jpg", secrets.hlsSecret()), token.get("poster_url").asText());
        // 与 master_url 同一签名（签的是 gen 目录）
        String sig = token.get("master_url").asText().split("/")[3];
        assertEquals(sig, token.get("poster_url").asText().split("/")[3]);

        JsonNode play = getJson("/api/v1/assets/" + asset + "/playback", "key-a");
        assertEquals(HlsSigner.v1GenFileUrl(play.get("expires_at").asLong(), asset, "34-2", "poster.jpg", secrets.hlsSecret()),
                play.get("poster_url").asText());
    }

    @Test
    void noPosterOrStaleKeyGivesNullNot500() throws Exception {
        long none = asset("READY");
        jdbc.update("UPDATE vod_media_asset SET hls_prefix = ? WHERE id = ?", "assets/v1/" + none + "/5-1/", none);
        // 封面键不在当前前缀（前缀已切到新 gen，但封面还指旧 gen）：不签
        long stale = asset("READY");
        jdbc.update("UPDATE vod_media_asset SET hls_prefix = ?, poster_key = ? WHERE id = ?",
                "assets/v1/" + stale + "/6-2/", "assets/v1/" + stale + "/6-1/poster.jpg", stale);
        // 旧固定前缀
        long legacy = asset("READY");
        jdbc.update("UPDATE vod_media_asset SET hls_prefix = ?, poster_key = ? WHERE id = ?",
                "assets/v1/" + legacy + "/", "assets/v1/" + legacy + "/poster.jpg", legacy);
        long uploading = asset("UPLOADING");

        for (long id : List.of(none, stale, legacy, uploading)) {
            assertTrue(getJson("/api/v1/assets/" + id, "key-a").get("poster_url").isNull(), "asset " + id);
        }
        for (JsonNode a : getJson("/api/v1/assets", "key-a").get("assets")) {
            assertTrue(a.has("poster_url") && a.get("poster_url").isNull(), a.toString());
        }
        JsonNode token = json.readTree(mvc.perform(post("/api/v1/assets/" + none + "/playback-token").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(token.get("poster_url").isNull());
    }

    @Test
    void otherTenantStill404() throws Exception {
        long asset = asset("READY");
        String prefix = "assets/v1/" + asset + "/7-1/";
        jdbc.update("UPDATE vod_media_asset SET hls_prefix = ?, poster_key = ? WHERE id = ?", prefix, prefix + "poster.jpg", asset);
        mvc.perform(get("/api/v1/assets/" + asset).header("X-Api-Key", "key-b")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", "key-b")).andExpect(status().isNotFound());
        assertEquals(0, getJson("/api/v1/assets", "key-b").get("assets").size());
    }

    private void assertPoster(String url, long asset, String gen, long before) {
        String[] parts = url.split("/");  // "", hls, v1, sig, exp, asset, gen, file
        long exp = Long.parseLong(parts[4]);
        assertTrue(exp >= before + 600 && exp <= Instant.now().getEpochSecond() + 600, url);
        assertEquals(HlsSigner.v1GenFileUrl(exp, asset, gen, "poster.jpg", secrets.hlsSecret()), url);
    }

    private long asset(String status) {
        jdbc.update("INSERT INTO vod_media_asset(tenant_id, status, source_bucket, source_key, encrypted) VALUES (1,?,?,?,0)",
                status, "vod-source", "raw/x/source.mp4");
        return jdbc.queryForObject("SELECT MAX(id) FROM vod_media_asset", Long.class);
    }

    private JsonNode getJson(String path, String key) throws Exception {
        return json.readTree(mvc.perform(get(path).header("X-Api-Key", key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
