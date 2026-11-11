package vod.admin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdminJwtTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripAndRejections() {
        String t = AdminJwt.issue(new AdminJwt.Claims(7, "ops", 1000, 2000), KEY);
        AdminJwt.Claims c = AdminJwt.verify(t, KEY, 1500, 0);
        assertNotNull(c);
        assertEquals("ops", c.username());
        assertEquals(7, c.userId());
        assertNull(AdminJwt.verify(t, KEY, 2000, 0), "expired at exp");
        assertNotNull(AdminJwt.verify(t, KEY, 2010, 30), "leeway");
        assertNull(AdminJwt.verify(t, KEY, 900, 0), "issued in the future");
        assertNull(AdminJwt.verify(t.substring(AdminJwt.PREFIX.length()), KEY, 1500, 0), "prefix required");
        assertNull(AdminJwt.verify(t, "x".getBytes(StandardCharsets.UTF_8), 1500, 0), "wrong key");
        assertNull(AdminJwt.verify(t + "x", KEY, 1500, 0));
        assertNull(AdminJwt.verify("vpt1.abc.def", KEY, 1500, 0), "play token is not an admin token");
        // 篡改 claims（改用户名）后签名不对
        String[] p = t.split("\\.");
        String other = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"vod-api\",\"aud\":\"vod-admin\",\"sub\":\"root\",\"uid\":1,\"iat\":1000,\"exp\":9999}".getBytes());
        assertNull(AdminJwt.verify(p[0] + "." + p[1] + "." + other + "." + p[3], KEY, 1500, 0));
    }
}
