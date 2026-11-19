package vod.media;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vod.security.SecretGuard;
import vod.storage.ObjectStorage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路线 C：给不能给分片和 key 请求加请求头的原生 HLS 播放器（iPhone / Safari）用的播放列表。
 *
 * <p>和旧 Demo 的 /api/playback/{asset}/master.m3u8 同一个思路：现读存储里的播放列表、现改写——
 * master 里的子列表指回本接口（带 token），子列表里的分片改成绝对的 /hls/v1/ 签名地址（仍走 Nginx 签名和缓存，
 * 签名与 playback-token 的 master_url 相同、过期时间取 token 的过期时间），#EXT-X-KEY 的 URI 加上 ?token=
 * （/keys/v1/ 本来就接受 ?token=）。hls.js 继续直接用 master_url，不经过这里。
 *
 * <p>鉴权只认 Core 播放 token（vpt1.），不认租户 Key（不在 ApiKeyFilter 里）：token 无效、过期、资产不匹配、
 * 资产不是 READY 或不属于 token 的租户，一律 401。响应 no-store。
 */
@RestController
public class NativeHlsController {
    public static final String PATH = "/api/v1/native-hls/";
    private static final Pattern VARIANT = Pattern.compile("([A-Za-z0-9_-]{1,32})/index\\.m3u8");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern RENDITION = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern KEY_URI = Pattern.compile("URI=\"([^\"?]*)(\\?[^\"]*)?\"");
    private static final int MAX_PLAYLIST_BYTES = 1 << 20;
    private static final MediaType M3U8 = MediaType.parseMediaType("application/vnd.apple.mpegurl");

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final SecretGuard secrets;
    private final MeterRegistry registry;
    private final String bucket;

    public NativeHlsController(JdbcTemplate jdbc, ObjectStorage storage, SecretGuard secrets, MeterRegistry registry,
                               @Value("${vod.hls.bucket:vod-hls}") String bucket) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.secrets = secrets;
        this.registry = registry;
        this.bucket = bucket;
    }

    /** 给 playback-token 响应用：同一个 token 的原生播放入口。 */
    public static String masterUrl(long assetId, String token) {
        return PATH + assetId + "/master.m3u8?token=" + enc(token);
    }

    @GetMapping(PATH + "{assetId}/master.m3u8")
    public ResponseEntity<String> master(@PathVariable long assetId, @RequestParam(value = "token", required = false) String token) {
        var ctx = authorize(assetId, token);
        if (ctx.isEmpty()) return deny();
        var text = storage.getText(bucket, ctx.get().prefix() + "master.m3u8", MAX_PLAYLIST_BYTES);
        if (text.isEmpty()) return notFound();
        var out = new StringBuilder();
        for (String line : text.get().split("\r?\n")) {
            Matcher m = VARIANT.matcher(line.strip());
            if (m.matches()) {
                out.append(PATH).append(assetId).append("/media.m3u8?rendition=").append(m.group(1))
                        .append("&token=").append(enc(token)).append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        registry.counter("vod.native_hls", "result", "master").increment();
        return ok(out.toString());
    }

    @GetMapping(PATH + "{assetId}/media.m3u8")
    public ResponseEntity<String> media(@PathVariable long assetId,
                                        @RequestParam(value = "rendition", required = false) String rendition,
                                        @RequestParam(value = "token", required = false) String token) {
        if (rendition == null || !RENDITION.matcher(rendition).matches()) return notFound();
        var ctx = authorize(assetId, token);
        if (ctx.isEmpty()) return deny();
        var text = storage.getText(bucket, ctx.get().prefix() + rendition + "/index.m3u8", MAX_PLAYLIST_BYTES);
        if (text.isEmpty()) return notFound();
        String dir = ctx.get().signedDir() + "/" + rendition + "/";
        String q = "?token=" + enc(token);
        var out = new StringBuilder();
        for (String line : text.get().split("\r?\n")) {
            String s = line.strip();
            if (s.startsWith("#EXT-X-KEY")) {
                Matcher m = KEY_URI.matcher(line);
                out.append(m.find() ? line.substring(0, m.start()) + "URI=\"" + m.group(1) + q + "\"" + line.substring(m.end()) : line);
            } else if (!s.isEmpty() && !s.startsWith("#")) {
                String name = s.contains("?") ? s.substring(0, s.indexOf('?')) : s;
                name = name.substring(name.lastIndexOf('/') + 1);
                out.append(SEGMENT.matcher(name).matches() && !name.contains("..") ? dir + name : line);
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        registry.counter("vod.native_hls", "result", "media").increment();
        return ok(out.toString());
    }

    private record Ctx(String prefix, String signedDir) {}

    private Optional<Ctx> authorize(long assetId, String token) {
        var claims = token == null ? null
                : CorePlayToken.verify(token.strip(), secrets.playTokenSecret(), Instant.now().getEpochSecond(), 0);
        if (claims == null || claims.assetId() != assetId) return Optional.empty();
        var rows = jdbc.query("SELECT tenant_id, status, hls_prefix FROM vod_media_asset WHERE id = ?",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}, assetId);
        if (rows.isEmpty() || (Long) rows.get(0)[0] != claims.tenantId() || !"READY".equals(rows.get(0)[1])) return Optional.empty();
        String prefix = (String) rows.get(0)[2];
        if (prefix == null) return Optional.empty();
        String secret = secrets.hlsSecret();
        long exp = claims.exp();
        // 签名目录：与 PlaybackService.masterUrl 同一套（有 gen 签 gen 目录，Phase 4 之前的固定前缀签资产目录）。
        String dir = PlaybackService.generation(assetId, prefix)
                .map(gen -> "/hls/v1/" + HlsSigner.v1GenSignature(exp, assetId, gen, secret) + "/" + exp + "/" + assetId + "/" + gen)
                .orElseGet(() -> "/hls/v1/" + HlsSigner.v1Signature(exp, assetId, secret) + "/" + exp + "/" + assetId);
        return Optional.of(new Ctx(prefix.endsWith("/") ? prefix : prefix + "/", dir));
    }

    private ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(M3U8).body(body);
    }

    private ResponseEntity<String> deny() {
        registry.counter("vod.native_hls", "result", "denied").increment();
        return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"invalid or expired play token\"}");
    }

    private ResponseEntity<String> notFound() {
        return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"playlist not found\"}");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
