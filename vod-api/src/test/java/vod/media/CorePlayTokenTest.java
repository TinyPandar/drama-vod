package vod.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorePlayTokenTest {
    private static final byte[] SECRET = "core-play-secret".getBytes(StandardCharsets.UTF_8);
    private static final long NOW = 1_700_000_000L;

    @Test
    void issueAndVerifyRoundTrip() {
        String token = CorePlayToken.issue(new CorePlayToken.Claims(3, 12, NOW + 60, "viewer-1"), SECRET);
        assertTrue(token.startsWith("vpt1."));
        assertEquals(2, token.chars().filter(c -> c == '.').count());
        var claims = CorePlayToken.verify(token, SECRET, NOW, 0);
        assertNotNull(claims);
        assertEquals(3, claims.tenantId());
        assertEquals(12, claims.assetId());
        assertEquals(NOW + 60, claims.exp());
        assertEquals("viewer-1", claims.viewerId());
        assertNull(CorePlayToken.verify(token, "other-secret".getBytes(StandardCharsets.UTF_8), NOW, 0));
    }

    @Test
    void forgedSignatureAndTamperedPayloadAreRejected() {
        String token = CorePlayToken.issue(new CorePlayToken.Claims(3, 12, NOW + 60, null), SECRET);
        String sig = token.substring(token.lastIndexOf('.') + 1);
        String forged = token.substring(0, token.lastIndexOf('.') + 1) + (sig.charAt(0) == 'A' ? 'B' : 'A') + sig.substring(1);
        assertNull(CorePlayToken.verify(forged, SECRET, NOW, 0));
        // 改 asset：换 payload、保留原签名
        String otherPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"t\":3,\"a\":13,\"exp\":1700000060}".getBytes(StandardCharsets.UTF_8));
        assertNull(CorePlayToken.verify("vpt1." + otherPayload + "." + sig, SECRET, NOW, 0));
        assertNull(CorePlayToken.verify(token.replace("vpt1.", "vpt2."), SECRET, NOW, 0));
        for (String junk : new String[]{null, "", "vpt1.", "vpt1..", "vpt1.a.b.c", "vpt1.abc.!!!", "abc.def"}) {
            assertNull(CorePlayToken.verify(junk, SECRET, NOW, 0), String.valueOf(junk));
        }
    }

    @Test
    void expiryAndGrace() {
        String token = CorePlayToken.issue(new CorePlayToken.Claims(3, 12, NOW, null), SECRET);
        assertNotNull(CorePlayToken.verify(token, SECRET, NOW, 0));
        assertNull(CorePlayToken.verify(token, SECRET, NOW + 1, 0));
        assertNotNull(CorePlayToken.verify(token, SECRET, NOW + 300, 300));  // QoE 宽限期内
        assertNull(CorePlayToken.verify(token, SECRET, NOW + 301, 300));
    }

    @Test
    void legacyAndCoreTokensDoNotInteroperate() {
        byte[] secret = "dev".getBytes(StandardCharsets.UTF_8);
        String legacy = PlayToken.issue(7, 12, NOW + 60, secret);
        String core = CorePlayToken.issue(new CorePlayToken.Claims(1, 12, NOW + 60, null), secret);
        // 即使两边密钥碰巧相同，格式也不通用
        assertNull(CorePlayToken.verify(legacy, secret, NOW, 0));
        assertFalse(PlayToken.valid(core, 12, secret, NOW));
        assertTrue(PlayToken.valid(legacy, 12, secret, NOW));
    }

    @Test
    void viewerIdIsOpaqueButBounded() {
        assertTrue(CorePlayToken.validViewerId(null));
        assertTrue(CorePlayToken.validViewerId("u:42.a_b-c"));
        assertFalse(CorePlayToken.validViewerId(""));
        assertFalse(CorePlayToken.validViewerId("a b"));
        assertFalse(CorePlayToken.validViewerId("x".repeat(65)));
        assertThrows(IllegalArgumentException.class,
                () -> CorePlayToken.issue(new CorePlayToken.Claims(1, 2, 3, "bad id"), SECRET));
    }
}
