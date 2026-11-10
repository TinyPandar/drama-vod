package vod.key;

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
import vod.media.PlayToken;
import vod.security.SecretGuard;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /keys/v1/{asset_id}：Core token 鉴权，不走 ApiKeyFilter。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KeyControllerTest {
    private static final String KEY_HEX = "00112233445566778899aabbccddeeff";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecretGuard secrets;

    @BeforeEach
    void seed() {
        for (String t : List.of("vod_content_key", "vod_usage_daily", "vod_media_asset", "vod_api_credential", "vod_tenant")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, ?, 'asset')", ApiKeys.sha256("key-a"));
    }

    @Test
    void validTokenGetsSixteenBytesWithNoStore() throws Exception {
        long asset = encryptedReadyAsset(1);
        String token = token(1, asset, 60);
        byte[] viaHeader = mvc.perform(get("/keys/v1/" + asset).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("no-store"), org.hamcrest.Matchers.containsString("private"))))
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(HexFormat.of().parseHex(KEY_HEX), viaHeader);
        byte[] viaQuery = mvc.perform(get("/keys/v1/" + asset).param("token", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(viaHeader, viaQuery);
    }

    @Test
    void tokenFromPlaybackTokenEndpointWorks() throws Exception {
        long asset = encryptedReadyAsset(1);
        String body = mvc.perform(post("/api/v1/assets/" + asset + "/playback-token").header("X-Api-Key", "key-a"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("token").asText();
        mvc.perform(get("/keys/v1/" + asset).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void invalidTokensAreUnauthorizedAndNoStore() throws Exception {
        long asset = encryptedReadyAsset(1);
        long other = encryptedReadyAsset(1);
        String good = token(1, asset, 60);
        String forged = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");
        String expired = token(1, asset, -5);
        String otherAsset = token(1, other, 60);
        String wrongSecret = CorePlayToken.issue(new CorePlayToken.Claims(1, asset, now() + 60, null),
                "not-the-secret".getBytes(StandardCharsets.UTF_8));
        String legacy = PlayToken.issue(7, asset, now() + 60, secrets.playTokenSecret());
        mvc.perform(get("/keys/v1/" + asset)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        for (String bad : List.of(forged, expired, otherAsset, wrongSecret, legacy, "garbage")) {
            mvc.perform(get("/keys/v1/" + asset).header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
            mvc.perform(get("/keys/v1/" + asset).param("token", bad)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void assetMustBelongToTokenTenantBeEncryptedAndReady() throws Exception {
        long asset = encryptedReadyAsset(1);
        // 别的租户签的 token（asset id 相同）：401
        mvc.perform(get("/keys/v1/" + asset).header("Authorization", "Bearer " + token(2, asset, 60)))
                .andExpect(status().isUnauthorized());
        long plain = insertAsset(1, "READY", 0);
        key(plain);
        mvc.perform(get("/keys/v1/" + plain).header("Authorization", "Bearer " + token(1, plain, 60)))
                .andExpect(status().isUnauthorized());
        long transcoding = insertAsset(1, "TRANSCODING", 1);
        key(transcoding);
        mvc.perform(get("/keys/v1/" + transcoding).header("Authorization", "Bearer " + token(1, transcoding, 60)))
                .andExpect(status().isUnauthorized());
        long missing = asset + 1000;
        mvc.perform(get("/keys/v1/" + missing).header("Authorization", "Bearer " + token(1, missing, 60)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readyEncryptedAssetWithoutKeyIsNotFound() throws Exception {
        long asset = insertAsset(1, "READY", 1);
        mvc.perform(get("/keys/v1/" + asset).header("Authorization", "Bearer " + token(1, asset, 60)))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    private String token(long tenantId, long assetId, long ttl) {
        return CorePlayToken.issue(new CorePlayToken.Claims(tenantId, assetId, now() + ttl, null), secrets.playTokenSecret());
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private long encryptedReadyAsset(long tenantId) {
        long id = insertAsset(tenantId, "READY", 1);
        key(id);
        return id;
    }

    private void key(long assetId) {
        jdbc.update("INSERT INTO vod_content_key(asset_id, key_hex, iv_hex) VALUES (?,?,?)", assetId, KEY_HEX, KEY_HEX);
    }

    private long insertAsset(long tenantId, String status, int encrypted) {
        jdbc.update("INSERT INTO vod_media_asset(tenant_id, status, source_bucket, source_key, encrypted) VALUES (?,?,?,?,?)",
                tenantId, status, "vod-source", "raw/x/source.mp4", encrypted);
        return jdbc.queryForObject("SELECT MAX(id) FROM vod_media_asset", Long.class);
    }
}
