package vod.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享向量 docs/contracts/hls-sign-vectors.json：scripts/test_playback_contract.py 用 Nginx 模板里的 secure_link_md5
 * 表达式算同一份向量，两边都对上才说明 Java 与 Nginx 一致。
 */
class PlaybackSignVectorsTest {
    @Test
    void javaSignerMatchesSharedVectors() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(Path.of("..", "docs", "contracts", "hls-sign-vectors.json")));
        String secret = root.get("secret").asText();
        int gens = 0;
        for (JsonNode v : root.get("vectors")) {
            long exp = v.get("exp").asLong();
            long asset = v.get("asset_id").asLong();
            String kind = v.get("kind").asText();
            String url = switch (kind) {
                case "legacy" -> HlsSigner.masterUrl(exp, asset, secret);
                case "v1" -> HlsSigner.v1MasterUrl(exp, asset, secret);
                case "v1gen" -> {
                    gens++;
                    assertEquals(v.get("signature").asText(), HlsSigner.v1GenSignature(exp, asset, v.get("gen").asText(), secret));
                    yield HlsSigner.v1GenMasterUrl(exp, asset, v.get("gen").asText(), secret);
                }
                default -> throw new AssertionError(kind);
            };
            assertEquals(v.get("master_url").asText(), url, kind);
        }
        assertTrue(gens >= 1);
    }

    @Test
    void generationSignatureCoversGenAndRejectsMalformedGen() {
        String a = HlsSigner.v1GenSignature(1_700_000_000L, 12, "34-2", "s");
        assertNotEquals(a, HlsSigner.v1GenSignature(1_700_000_000L, 12, "34-3", "s"));
        assertNotEquals(a, HlsSigner.v1Signature(1_700_000_000L, 12, "s"));
        for (String bad : new String[]{"", "34", "34-2-1", "a-1", "../1-2", "1-2/", " 1-2"}) {
            assertFalse(HlsSigner.validGeneration(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> HlsSigner.v1GenSignature(1, 12, bad, "s"), bad);
        }
    }

    @Test
    void onlyMatchingGenerationPrefixesUseGenUrls() {
        assertEquals("34-2", PlaybackService.generation(12, "assets/v1/12/34-2/").orElseThrow());
        assertTrue(PlaybackService.generation(12, "assets/v1/12/").isEmpty());   // Phase 4 之前的固定前缀
        assertTrue(PlaybackService.generation(12, "assets/12/").isEmpty());
        assertTrue(PlaybackService.generation(12, "assets/v1/13/34-2/").isEmpty()); // 别的资产的前缀不认
        assertTrue(PlaybackService.generation(12, "assets/v1/12/34-2/x/").isEmpty());
        assertTrue(PlaybackService.generation(12, null).isEmpty());
    }
}
