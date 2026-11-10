package vod.media;

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
import java.util.regex.Pattern;

/**
 * Core 播放 token（Phase 4）。和旧 Demo 的 {@link PlayToken} 互不通用：
 *
 * <pre>
 * payload = base64url(无填充)( {"t":tenant_id,"a":asset_id,"exp":exp[,"v":viewer_id]} )
 * token   = "vpt1." + payload + "." + base64url(无填充)( hmac_sha256(VOD_PLAY_TOKEN_SECRET, "vpt1." + payload) )
 * </pre>
 *
 * 版本前缀 vpt1. 让旧 /keys/{id}（只认「一个点」的旧格式、TOKEN_SECRET）拒绝它；本类也拒绝没有前缀的旧 token。
 * viewer_id 是调用方给的不透明串，只用于日志/QoE，不入库，也不参与授权。
 */
public final class CorePlayToken {
    public static final String PREFIX = "vpt1.";
    private static final Pattern VIEWER = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private CorePlayToken() {}

    public record Claims(long tenantId, long assetId, long exp, String viewerId) {}

    public static boolean validViewerId(String viewerId) {
        return viewerId == null || VIEWER.matcher(viewerId).matches();
    }

    public static String issue(Claims claims, byte[] secret) {
        if (!validViewerId(claims.viewerId())) throw new IllegalArgumentException("viewer_id");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("t", claims.tenantId());
        body.put("a", claims.assetId());
        body.put("exp", claims.exp());
        if (claims.viewerId() != null) body.put("v", claims.viewerId());
        try {
            String signed = PREFIX + b64(JSON.writeValueAsBytes(body));
            return signed + "." + b64(hmac(secret, signed));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 签名、格式、过期都对才返回 claims，否则 null。graceSeconds：过期后仍接受的秒数（只给 QoE 上报用，取 key 传 0）。 */
    public static Claims verify(String token, byte[] secret, long nowEpochSeconds, long graceSeconds) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 1024) return null;
        String rest = token.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot != rest.lastIndexOf('.') || dot == rest.length() - 1) return null;
        String payload = rest.substring(0, dot);
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(rest.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!MessageDigest.isEqual(hmac(secret, PREFIX + payload), given)) return null;
        JsonNode node;
        try {
            node = JSON.readTree(Base64.getUrlDecoder().decode(payload));
        } catch (Exception e) {
            return null;
        }
        if (node == null || !node.isObject()) return null;
        JsonNode t = node.get("t"), a = node.get("a"), exp = node.get("exp"), v = node.get("v");
        if (!integral(t) || !integral(a) || !integral(exp)) return null;
        String viewer = null;
        if (v != null && !v.isNull()) {
            if (!v.isTextual() || !validViewerId(v.asText())) return null;
            viewer = v.asText();
        }
        if (exp.asLong() + Math.max(0, graceSeconds) < nowEpochSeconds) return null;
        return new Claims(t.asLong(), a.asLong(), exp.asLong(), viewer);
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
