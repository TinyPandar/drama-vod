package vod.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动时检查 vod-api 用到的签名密钥：HLS_SECRET（播放地址签名，和 Nginx 共用）、VOD_PLAY_TOKEN_SECRET（Core 播放 token）。
 *
 * <p>VOD_ENV 不是 dev 时，任一密钥为空、等于 dev 或短于 vod.security.min-secret-length 就抛异常，Spring 上下文起不来；
 * dev 下只打警告，VOD_PLAY_TOKEN_SECRET 为空时退回 dev，保证本地一条命令能起栈。日志只写变量名，不写值。
 */
@Component
public class SecretGuard {
    private static final Logger log = LoggerFactory.getLogger(SecretGuard.class);
    static final String DEV_FALLBACK = "dev";

    private final boolean dev;
    private final String hlsSecret;
    private final byte[] playTokenSecret;

    public SecretGuard(@Value("${vod.env:dev}") String env,
                       @Value("${vod.hls-secret:dev}") String hlsSecret,
                       @Value("${vod.play-token-secret:}") String playTokenSecret,
                       @Value("${vod.security.min-secret-length:32}") int minLength) {
        this.dev = isDev(env);
        List<String> weak = new ArrayList<>();
        if (weak(hlsSecret, minLength)) weak.add("HLS_SECRET");
        if (weak(playTokenSecret, minLength)) weak.add("VOD_PLAY_TOKEN_SECRET");
        if (!weak.isEmpty()) {
            String msg = "weak or missing secrets " + weak + " (empty, 'dev' or shorter than " + minLength + " chars)";
            if (!dev) throw new IllegalStateException(msg + "; refusing to start with VOD_ENV=" + env);
            log.warn("{}; allowed only because VOD_ENV=dev", msg);
        }
        this.hlsSecret = hlsSecret == null ? "" : hlsSecret;
        String token = playTokenSecret == null || playTokenSecret.isBlank() ? DEV_FALLBACK : playTokenSecret;
        this.playTokenSecret = token.getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isDev(String env) {
        return env == null || env.isBlank() || env.trim().toLowerCase(Locale.ROOT).equals("dev");
    }

    static boolean weak(String value, int minLength) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase(DEV_FALLBACK)
                || value.length() < Math.max(1, minLength);
    }

    public boolean dev() {
        return dev;
    }

    public String hlsSecret() {
        return hlsSecret;
    }

    public byte[] playTokenSecret() {
        return playTokenSecret.clone();
    }
}
