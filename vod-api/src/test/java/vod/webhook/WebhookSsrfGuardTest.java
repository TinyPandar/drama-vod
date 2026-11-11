package vod.webhook;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSsrfGuardTest {
    static InetAddress a(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void classifiesIpv4AndIpv6() throws Exception {
        for (String bad : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "192.0.2.1", "198.18.0.1",
                "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
                "::10.0.0.1", "64:ff9b::a00:1", "2001:db8::1", "2002:a00:1::1", "2001:0:4136:e378::1", "fec0::1")) {
            assertFalse(WebhookUrlGuard.isPublic(a(bad)), bad);
        }
        for (String good : List.of("1.1.1.1", "8.8.8.8", "93.184.215.14", "172.32.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8",
                "64:ff9b::808:808")) {
            assertTrue(WebhookUrlGuard.isPublic(a(good)), good);
        }
    }

    @Test
    void urlShapeAndDevAllowList() {
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requirePublicHttps("https://[::1]:8443/x"));
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requirePublicHttps("https://hooks.example.com/x#f"));
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requireUrl("http://hooks.example.com/x", false));
        assertDoesNotThrow(() -> WebhookUrlGuard.requireUrl("http://hooks.example.com/x", true));
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requireUrl("http://host.docker.internal:9/x", true));
        assertDoesNotThrow(() -> WebhookUrlGuard.requireUrl("http://host.docker.internal:9/x", true, Set.of("host.docker.internal")));
        assertDoesNotThrow(() -> WebhookUrlGuard.requireResolvedDevAllowed(List.of(InetAddress.getLoopbackAddress())));
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requireResolvedDevAllowed(List.of(a("169.254.169.254"))));
    }

    @Test
    void devSwitchesRefuseToStartOutsideDev() {
        var prod = new vod.security.SecretGuard("prod", "0123456789abcdef0123456789abcdef",
                "fedcba9876543210fedcba9876543210", "abcdefabcdefabcdefabcdefabcdefab", 32);
        WebhookService.DnsLookup dns = host -> List.of();
        assertThrows(IllegalStateException.class, () -> new WebhookTargetPolicy(prod, dns, true, ""));
        assertThrows(IllegalStateException.class, () -> new WebhookTargetPolicy(prod, dns, false, "host.docker.internal"));
        assertDoesNotThrow(() -> new WebhookTargetPolicy(prod, dns, false, ""));
    }
}
