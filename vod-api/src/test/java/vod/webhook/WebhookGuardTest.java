package vod.webhook;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookGuardTest {
    @Test
    void rejectsPrivateAndPlaintextTargets() {
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requirePublicHttps("http://hooks.example.com/v"));
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requirePublicHttps("https://127.0.0.1/hook"));
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requirePublicHttps("https://169.254.169.254/latest"));
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requirePublicHttps("https://localhost/hook"));
        assertDoesNotThrow(() -> WebhookUrlGuard.requirePublicHttps("https://hooks.example.com/vod"));
    }

    @Test
    void rejectsResolvedPrivateAddress() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WebhookUrlGuard.requireResolvedPublic(
                List.of(InetAddress.getByName("127.0.0.1"))));
        assertDoesNotThrow(() -> WebhookUrlGuard.requireResolvedPublic(List.of(InetAddress.getByName("1.1.1.1"))));
    }
}
