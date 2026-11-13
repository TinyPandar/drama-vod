package vod.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vod.security.SecretGuard;

import java.time.Instant;
import java.util.Optional;

/**
 * Phase 6：资产封面的签名地址。封面只认落在资产<b>当前</b> hls_prefix（带 gen 的任务专属前缀）里的 poster_key，
 * 用与 master.m3u8 相同的目录签名；对不上（旧固定前缀、前缀已切换、键不在前缀里）就当没有封面，返回 empty，不报错。
 *
 * <p>资产列表 / 详情的过期时间与 GET .../playback 相同（vod.play-ttl-seconds），同样不超过 token-ttl-max-seconds；
 * playback-token 用和 master_url 相同的 exp。签名 URL 会过期，不写进 Webhook 事件体。
 */
@Component
public class PosterUrls {
    private final SecretGuard secrets;
    private final long ttlSeconds;

    public PosterUrls(SecretGuard secrets,
                      @Value("${vod.play-ttl-seconds:600}") long urlTtlSeconds,
                      @Value("${vod.playback.token-ttl-max-seconds:3600}") long tokenTtlMaxSeconds) {
        this.secrets = secrets;
        this.ttlSeconds = Math.max(1, Math.min(urlTtlSeconds, tokenTtlMaxSeconds));
    }

    /** 列表 / 详情用的默认过期时间（unix 秒）。 */
    public long defaultExpiry() {
        return Instant.now().getEpochSecond() + ttlSeconds;
    }

    public String urlOrNull(long assetId, String hlsPrefix, String posterKey, long exp) {
        return url(assetId, hlsPrefix, posterKey, exp).orElse(null);
    }

    public Optional<String> url(long assetId, String hlsPrefix, String posterKey, long exp) {
        if (posterKey == null || hlsPrefix == null || !posterKey.startsWith(hlsPrefix)) return Optional.empty();
        Optional<String> gen = PlaybackService.generation(assetId, hlsPrefix);
        if (gen.isEmpty()) return Optional.empty();
        String file = posterKey.substring(hlsPrefix.length());
        if (!file.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) return Optional.empty();
        return Optional.of(HlsSigner.v1GenFileUrl(exp, assetId, gen.get(), file, secrets.hlsSecret()));
    }
}
