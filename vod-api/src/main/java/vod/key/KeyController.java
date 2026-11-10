package vod.key;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vod.media.CorePlayToken;
import vod.security.SecretGuard;

import java.time.Instant;
import java.util.Map;

/**
 * Core 加密资产的 key 接口：GET /keys/v1/{asset_id}。不在 /api/v1 下，不走 ApiKeyFilter，凭 Core 播放 token。
 *
 * <p>与旧 /keys/{asset_id} 语义对齐：token 缺失/无效/过期、asset 不一致、资产不属于 token 的租户、未加密、未 READY 都是 401；
 * 资产没有 key 是 404。token 可放在 Authorization: Bearer 或 ?token=（给不能加头的播放器）。所有响应 no-store, private。
 * 旧 Demo token 在这里无效，Core token 在旧接口也无效（格式和密钥都不同）。
 */
@RestController
public class KeyController {
    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final JdbcTemplate jdbc;
    private final KeyProvider keys;
    private final SecretGuard secrets;
    private final MeterRegistry registry;

    public KeyController(JdbcTemplate jdbc, KeyProvider keys, SecretGuard secrets, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.secrets = secrets;
        this.registry = registry;
    }

    @GetMapping("/keys/v1/{assetId}")
    public ResponseEntity<?> key(@PathVariable long assetId,
                                 @RequestParam(value = "token", required = false) String token,
                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        String raw = token != null && !token.isBlank() ? token.trim() : bearer(authorization);
        var claims = CorePlayToken.verify(raw, secrets.playTokenSecret(), Instant.now().getEpochSecond(), 0);
        if (claims == null || claims.assetId() != assetId || !servable(claims.tenantId(), assetId)) {
            return deny(401, "denied", "invalid or expired play token");
        }
        var key = keys.contentKey(assetId);
        if (key.isEmpty()) return deny(404, "not_found", "key not found");
        count("ok");
        return ResponseEntity.ok().cacheControl(NO_STORE).contentType(MediaType.APPLICATION_OCTET_STREAM).body(key.get());
    }

    /** 资产存在、属于 token 的租户、加密且 READY。 */
    private boolean servable(long tenantId, long assetId) {
        var rows = jdbc.query("SELECT tenant_id, status, encrypted FROM vod_media_asset WHERE id = ?",
                (rs, n) -> rs.getLong("tenant_id") == tenantId && "READY".equals(rs.getString("status"))
                        && rs.getInt("encrypted") == 1,
                assetId);
        return !rows.isEmpty() && rows.get(0);
    }

    private ResponseEntity<Map<String, String>> deny(int status, String result, String detail) {
        count(result);
        return ResponseEntity.status(status).cacheControl(NO_STORE).body(Map.of("detail", detail));
    }

    private void count(String result) {
        registry.counter("vod.core.key.requests", "result", result).increment();
    }

    private static String bearer(String authorization) {
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }
}
