package vod.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端 token（Phase 5）：版本前缀 + 标准 HS256 JWT。
 *
 * <pre>
 * jwt   = base64url({"alg":"HS256","typ":"JWT","kid":"v1"}) + "." + base64url(claims) + "." + base64url(hmac_sha256(VOD_ADMIN_JWT_SECRET, 前两段))
 * token = "vadm1." + jwt
 * claims = {"iss":"vod-api","aud":"vod-admin","sub":用户名,"uid":管理用户 id,"iat":签发秒,"exp":过期秒}
 * </pre>
 *
 * 前缀让它和租户 API Key、Core 播放 token（vpt1.）在格式上就分得开；校验时只认 HS256，拒绝 alg=none 和别的算法，
 * aud 必须是 vod-admin。密钥和租户侧、播放侧都不同。
 */
public final class AdminJwt {
    public static final String PREFIX = "vadm1.";
    static final String ISSUER = "vod-api";
    static final String AUDIENCE = "vod-admin";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\",\"kid\":\"v1\"}".getBytes(StandardCharsets.UTF_8));

    private AdminJwt() {}

    public record Claims(long userId, String username, long issuedAt, long expiresAt) {}

    public static String issue(Claims claims, byte[] secret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("iss", ISSUER);
        body.put("aud", AUDIENCE);
        body.put("sub", claims.username());
        body.put("uid", claims.userId());
        body.put("iat", claims.issuedAt());
        body.put("exp", claims.expiresAt());
        try {
            String signingInput = HEADER + "." + b64(JSON.writeValueAsBytes(body));
            return PREFIX + signingInput + "." + b64(hmac(secret, signingInput));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 前缀、算法、签名、aud/iss、过期都对才返回 claims，否则 null。leewaySeconds 只容忍时钟偏差。 */
    public static Claims verify(String token, byte[] secret, long nowEpochSeconds, long leewaySeconds) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 2048) return null;
        String jwt = token.substring(PREFIX.length());
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) return null;
        JsonNode header = parse(parts[0]);
        if (header == null || !"HS256".equals(text(header, "alg")) || !"v1".equals(text(header, "kid"))) return null;
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(hmac(secret, parts[0] + "." + parts[1]), given)) return null;
        JsonNode claims = parse(parts[1]);
        if (claims == null) return null;
        if (!ISSUER.equals(text(claims, "iss")) || !AUDIENCE.equals(text(claims, "aud"))) return null;
        String sub = text(claims, "sub");
        JsonNode uid = claims.get("uid"), iat = claims.get("iat"), exp = claims.get("exp");
        if (sub == null || sub.isBlank() || !integral(uid) || !integral(iat) || !integral(exp)) return null;
        long leeway = Math.max(0, leewaySeconds);
        if (exp.asLong() + leeway <= nowEpochSeconds) return null;
        if (iat.asLong() - leeway > nowEpochSeconds) return null;
        return new Claims(uid.asLong(), sub, iat.asLong(), exp.asLong());
    }

    private static JsonNode parse(String part) {
        try {
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(part));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }

    private static boolean integral(JsonNode n) {
        return n != null && n.isIntegralNumber() && n.canConvertToLong();
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] hmac(byte[] secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.length == 0 ? new byte[1] : secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
