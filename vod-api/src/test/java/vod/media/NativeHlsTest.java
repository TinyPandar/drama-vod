package vod.media;

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
import vod.security.SecretGuard;
import vod.storage.MemoryStorage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 路线 C：原生 HLS 播放列表改写（/api/v1/native-hls/），只认 Core 播放 token。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NativeHlsTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretGuard secrets;
    @Autowired MemoryStorage storage;
    private final ObjectMapper json = new ObjectMapper();

    private static final String MASTER = "#EXTM3U\n#EXT-X-VERSION:3\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=720x1280\n720p/index.m3u8\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=360x640\n360p/index.m3u8\n";
    private static final String MEDIA = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n"
            + "#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/v1/{id}\",IV=0x00112233445566778899aabbccddeeff\n"
            + "#EXTINF:1.0,\nseg_000.ts\n#EXTINF:4.0,\nseg_001.ts\n#EXT-X-ENDLIST\n";

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_usage_daily", "vod_media_asset", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, 'asset'), (2, ?, 'asset')",
                ApiKeys.sha256("key-a"), ApiKeys.sha256("key-b"));
        storage.clear();
    }

    @Test
    void playbackTokenCarriesANativeUrlThatRewritesMasterAndMedia() throws Exception {
        long asset = asset(1, "READY", "assets/v1/{id}/12-3/");
        JsonNode issued = issue(asset, "key-a");
        String token = issued.get("token").asText();
        long exp = issued.get("expires_at").asLong();
        assertEquals("/api/v1/native-hls/" + asset + "/master.m3u8?token=" + enc(token), issued.get("native_master_url").asText());

        String master = mvc.perform(get(issued.get("native_master_url").asText()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/vnd.apple.mpegurl")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(master.contains("/api/v1/native-hls/" + asset + "/media.m3u8?rendition=720p&token=" + enc(token)), master);
        assertTrue(master.contains("/api/v1/native-hls/" + asset + "/media.m3u8?rendition=360p&token=" + enc(token)), master);
        assertTrue(master.contains("#EXT-X-STREAM-INF:BANDWIDTH=2000000"), "其他行原样保留");
        assertFalse(master.contains("\n720p/index.m3u8"));

        String media = mvc.perform(get("/api/v1/native-hls/" + asset + "/media.m3u8").param("rendition", "720p").param("token", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String dir = "/hls/v1/" + HlsSigner.v1GenSignature(exp, asset, "12-3", secrets.hlsSecret()) + "/" + exp + "/" + asset + "/12-3/720p/";
        assertTrue(media.contains("\n" + dir + "seg_000.ts\n"), media);
        assertTrue(media.contains("\n" + dir + "seg_001.ts\n"), media);
        assertTrue(media.contains("URI=\"/keys/v1/" + asset + "?token=" + enc(token) + "\",IV=0x00112233445566778899aabbccddeeff"), media);
        // 分片签名目录与 playback-token 返回的 master_url 是同一个
        assertTrue(issued.get("master_url").asText().startsWith(dir.substring(0, dir.length() - "720p/".length())));
    }

    @Test
    void fixedPrefixAssetsSignTheAssetDirectory() throws Exception {
        long asset = asset(1, "READY", "assets/v1/{id}/");
        JsonNode issued = issue(asset, "key-a");
        long exp = issued.get("expires_at").asLong();
        String media = mvc.perform(get("/api/v1/native-hls/" + asset + "/media.m3u8").param("rendition", "360p")
                        .param("token", issued.get("token").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String dir = "/hls/v1/" + HlsSigner.v1Signature(exp, asset, secrets.hlsSecret()) + "/" + exp + "/" + asset + "/360p/";
        assertTrue(media.contains("\n" + dir + "seg_000.ts\n"), media);
    }

    @Test
    void anythingButAValidTokenForThisReadyAssetIs401() throws Exception {
        long asset = asset(1, "READY", "assets/v1/{id}/1-1/");
        long other = asset(1, "READY", "assets/v1/{id}/2-1/");
        String token = issue(asset, "key-a").get("token").asText();
        String path = "/api/v1/native-hls/" + asset + "/master.m3u8";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).param("token", "vpt1.garbage")).andExpect(status().isUnauthorized());
        mvc.perform(get(path).param("token", token + "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/native-hls/" + other + "/master.m3u8").param("token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-Api-Key", "key-a")).andExpect(status().isUnauthorized());
        String expired = CorePlayToken.issue(new CorePlayToken.Claims(1, asset, Instant.now().getEpochSecond() - 5, null),
                secrets.playTokenSecret());
        mvc.perform(get(path).param("token", expired)).andExpect(status().isUnauthorized());
        String wrongTenant = CorePlayToken.issue(new CorePlayToken.Claims(2, asset, Instant.now().getEpochSecond() + 60, null),
                secrets.playTokenSecret());
        mvc.perform(get(path).param("token", wrongTenant)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE vod_media_asset SET status = 'DELETED' WHERE id = ?", asset);
        mvc.perform(get(path).param("token", token)).andExpect(status().isUnauthorized());
    }

    @Test
    void renditionNamesAreValidatedAndMissingPlaylistsAre404() throws Exception {
        long asset = asset(1, "READY", "assets/v1/{id}/1-1/");
        String token = issue(asset, "key-a").get("token").asText();
        mvc.perform(get("/api/v1/native-hls/" + asset + "/media.m3u8").param("rendition", "../x").param("token", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/native-hls/" + asset + "/media.m3u8").param("rendition", "1080p").param("token", token))
                .andExpect(status().isNotFound());
    }

    private long asset(long tenant, String status, String prefixTemplate) {
        jdbc.update("INSERT INTO vod_media_asset(tenant_id, status, source_bucket, source_key, encrypted) VALUES (?, ?, 'vod-source', 'k', 1)",
                tenant, status);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM vod_media_asset", Long.class);
        String prefix = prefixTemplate.replace("{id}", Long.toString(id));
        jdbc.update("UPDATE vod_media_asset SET hls_prefix = ? WHERE id = ?", prefix, id);
        storage.putText("vod-hls", prefix + "master.m3u8", MASTER);
        storage.putText("vod-hls", prefix + "720p/index.m3u8", MEDIA.replace("{id}", Long.toString(id)));
        storage.putText("vod-hls", prefix + "360p/index.m3u8", MEDIA.replace("{id}", Long.toString(id)));
        return id;
    }

    private JsonNode issue(long asset, String key) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
