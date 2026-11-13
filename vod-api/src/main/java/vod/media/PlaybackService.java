package vod.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import vod.security.SecretGuard;
import vod.tenant.TenantGuard;
import vod.usage.UsageService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GET .../playback 和 POST .../playback-token 共用：租户可见性、只给 READY 签地址、按 hls_prefix 选签名路径、记一次播放用量。
 *
 * <p>hls_prefix 是 Phase 4 的任务专属前缀 assets/v1/{asset}/{gen}/ 时签带 gen 的地址；
 * 仍是旧固定前缀 assets/v1/{asset}/（或为空）时沿用原来的 /hls/v1/{sig}/{exp}/{asset}/master.m3u8。
 */
@Service
public class PlaybackService {
    private static final Pattern GEN_PREFIX = Pattern.compile("^assets/v1/([0-9]+)/([0-9]+-[0-9]+)/$");

    private final JdbcTemplate jdbc;
    private final UsageService usage;
    private final SecretGuard secrets;
    private final PosterUrls posters;
    private final long urlTtlSeconds;
    private final long tokenTtlSeconds;
    private final long tokenTtlMaxSeconds;

    /** TTL 都是配置项，默认值只是开发默认，不是基准或承诺。 */
    public PlaybackService(JdbcTemplate jdbc, UsageService usage, SecretGuard secrets, PosterUrls posters,
                           @Value("${vod.play-ttl-seconds:600}") long urlTtlSeconds,
                           @Value("${vod.playback.token-ttl-seconds:600}") long tokenTtlSeconds,
                           @Value("${vod.playback.token-ttl-max-seconds:3600}") long tokenTtlMaxSeconds) {
        if (urlTtlSeconds <= 0 || tokenTtlSeconds <= 0 || tokenTtlMaxSeconds <= 0) {
            throw new IllegalStateException("playback TTLs must be > 0");
        }
        this.jdbc = jdbc;
        this.usage = usage;
        this.secrets = secrets;
        this.posters = posters;
        this.urlTtlSeconds = urlTtlSeconds;
        this.tokenTtlSeconds = Math.min(tokenTtlSeconds, tokenTtlMaxSeconds);
        this.tokenTtlMaxSeconds = tokenTtlMaxSeconds;
    }

    /** posterKey：Phase 6 封面对象键（可空），只在落在当前 hlsPrefix 里时签 poster_url。 */
    public record ReadyAsset(long id, long tenantId, boolean encrypted, String hlsPrefix, String posterKey) {}

    /** 不存在或别的租户：404（不可见）；非 READY：409。 */
    public ReadyAsset readyAsset(long tenantId, long assetId) {
        var rows = jdbc.query("SELECT id, tenant_id, status, encrypted, hls_prefix, poster_key FROM vod_media_asset WHERE id = ?",
                (rs, n) -> Map.entry(rs.getString("status"),
                        new ReadyAsset(rs.getLong("id"), rs.getLong("tenant_id"), rs.getInt("encrypted") == 1,
                                rs.getString("hls_prefix"), rs.getString("poster_key"))),
                assetId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        TenantGuard.requireVisible(tenantId, rows.get(0).getValue().tenantId());
        if (!"READY".equals(rows.get(0).getKey())) throw new NotReady();
        return rows.get(0).getValue();
    }

    /** hls_prefix 指向任务专属前缀时返回其 gen。前缀里的资产 id 必须和本资产一致，否则当作旧前缀。 */
    public static Optional<String> generation(long assetId, String hlsPrefix) {
        if (hlsPrefix == null) return Optional.empty();
        Matcher m = GEN_PREFIX.matcher(hlsPrefix);
        if (!m.matches() || !m.group(1).equals(Long.toString(assetId))) return Optional.empty();
        return Optional.of(m.group(2));
    }

    public String masterUrl(ReadyAsset asset, long exp) {
        String secret = secrets.hlsSecret();
        return generation(asset.id(), asset.hlsPrefix())
                .map(gen -> HlsSigner.v1GenMasterUrl(exp, asset.id(), gen, secret))
                .orElseGet(() -> HlsSigner.v1MasterUrl(exp, asset.id(), secret));
    }

    /** 封面地址：与 master_url 同一个 exp；没有封面为 null。 */
    public String posterUrl(ReadyAsset asset, long exp) {
        return posters.urlOrNull(asset.id(), asset.hlsPrefix(), asset.posterKey(), exp);
    }

    /** GET .../playback：只签地址（Demo 在用，原有字段不变）。Phase 6 追加 poster_url（可为 null）。 */
    public Map<String, Object> playback(long tenantId, long assetId) {
        ReadyAsset asset = readyAsset(tenantId, assetId);
        usage.addPlay(tenantId);
        long exp = Instant.now().getEpochSecond() + urlTtlSeconds;
        var out = new LinkedHashMap<String, Object>();
        out.put("asset_id", assetId);
        out.put("master_url", masterUrl(asset, exp));
        out.put("expires_at", exp);
        out.put("poster_url", posterUrl(asset, exp));
        return out;
    }

    /**
     * POST .../playback-token：签 Core token 与同一过期时间的 master_url。ttl 缺省走 token-ttl-seconds，
     * 超过 token-ttl-max-seconds 时截到上限；&lt;= 0 是 400。
     */
    public Map<String, Object> issueToken(long tenantId, long assetId, Long ttlSeconds, String viewerId) {
        if (ttlSeconds != null && ttlSeconds <= 0) throw new IllegalArgumentException("ttl_seconds must be positive");
        if (!CorePlayToken.validViewerId(viewerId)) throw new IllegalArgumentException("viewer_id must match [A-Za-z0-9_.:-]{1,64}");
        ReadyAsset asset = readyAsset(tenantId, assetId);
        long ttl = ttlSeconds == null ? tokenTtlSeconds : Math.min(ttlSeconds, tokenTtlMaxSeconds);
        long exp = Instant.now().getEpochSecond() + ttl;
        String token = CorePlayToken.issue(new CorePlayToken.Claims(tenantId, assetId, exp, viewerId),
                secrets.playTokenSecret());
        usage.addPlay(tenantId);
        var out = new LinkedHashMap<String, Object>();
        out.put("asset_id", assetId);
        out.put("token", token);
        out.put("master_url", masterUrl(asset, exp));
        out.put("expires_at", exp);
        out.put("encrypted", asset.encrypted());
        out.put("poster_url", posterUrl(asset, exp));
        return out;
    }

    public static class NotReady extends RuntimeException {}
}
