package vod.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatVectorsTest {
    @Test
    void matchesPythonPlayToken() {
        byte[] secret = "dev-token".getBytes();
        String token = PlayToken.issue(7, 12, 1_700_000_000L, secret);
        assertEquals(
                "eyJ1IjogNywgImEiOiAxMiwgImV4cCI6IDE3MDAwMDAwMDB9.4516e581f5c8f7f78954cb65ce03b018dc23288ad1e02772f19e2044ca52542a",
                token);
        assertTrue(PlayToken.valid(token, 12, secret, 1_700_000_000L));
        assertFalse(PlayToken.valid(token, 13, secret, 1_700_000_000L));
        assertFalse(PlayToken.valid(token, 12, secret, 1_700_000_001L));
    }

    @Test
    void matchesPythonHlsSignature() {
        assertEquals("MH5Il_R3zWa5ZGqMLpd9AQ", HlsSigner.signature(1_700_000_000L, 12, "dev-hls"));
        assertEquals(
                "/hls/MH5Il_R3zWa5ZGqMLpd9AQ/1700000000/12/master.m3u8",
                HlsSigner.masterUrl(1_700_000_000L, 12, "dev-hls"));
        String v1 = HlsSigner.v1MasterUrl(1_700_000_000L, 12, "dev-hls");
        org.junit.jupiter.api.Assertions.assertTrue(v1.startsWith("/hls/v1/"));
        org.junit.jupiter.api.Assertions.assertNotEquals(HlsSigner.signature(1_700_000_000L, 12, "dev-hls"),
                HlsSigner.v1Signature(1_700_000_000L, 12, "dev-hls"));
    }
}
