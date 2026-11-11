package vod.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretGuardTest {
    private static final String STRONG = "0123456789abcdef0123456789abcdef";
    private static final String STRONG2 = "fedcba9876543210fedcba9876543210";

    @Test
    void nonDevRefusesMissingDevOrShortSecrets() {
        for (String weak : new String[]{"", "dev", "DEV", "short"}) {
            var e = assertThrows(IllegalStateException.class, () -> new SecretGuard("prod", weak, STRONG2, 32), weak);
            assertTrue(e.getMessage().contains("HLS_SECRET"));
            assertFalse(e.getMessage().contains(STRONG2), "never print secret values");
            assertThrows(IllegalStateException.class, () -> new SecretGuard("staging", STRONG, weak, 32), weak);
        }
        assertThrows(IllegalStateException.class, () -> new SecretGuard("prod", STRONG, null, 32));
        assertDoesNotThrow(() -> new SecretGuard("prod", STRONG, STRONG2, 32));
        assertDoesNotThrow(() -> new SecretGuard("prod", "abcdefgh", "12345678", 8));  // 最小长度可配置
    }

    @Test
    void devOnlyWarnsAndFallsBack() {
        var guard = assertDoesNotThrow(() -> new SecretGuard("dev", "dev", "", 32));
        assertTrue(guard.dev());
        assertArrayEquals("dev".getBytes(StandardCharsets.UTF_8), guard.playTokenSecret());
        assertTrue(SecretGuard.isDev(null));
        assertTrue(SecretGuard.isDev(" Dev "));
        assertFalse(SecretGuard.isDev("prod"));
    }

    @Test
    void springContextFailsToStartWithWeakSecretsOutsideDev() {
        var runner = new ApplicationContextRunner().withUserConfiguration(SecretGuard.class);
        runner.withPropertyValues("vod.env=prod", "vod.hls-secret=dev", "vod.play-token-secret=" + STRONG)
                .run(ctx -> assertThat(ctx).hasFailed());
        // Phase 5：非 dev 还要求 VOD_ADMIN_JWT_SECRET（vod.admin.jwt-secret）。
        runner.withPropertyValues("vod.env=prod", "vod.hls-secret=" + STRONG, "vod.play-token-secret=" + STRONG2,
                        "vod.admin.jwt-secret=" + STRONG.toUpperCase())
                .run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("vod.env=dev", "vod.hls-secret=", "vod.play-token-secret=")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
