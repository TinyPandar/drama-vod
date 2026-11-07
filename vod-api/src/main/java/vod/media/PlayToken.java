package vod.media;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 与 api/main.py make_token 一致：json.dumps 默认分隔符，键顺序 u、a、exp，base64url 保留填充。
 */
public final class PlayToken {
    private PlayToken() {}

    public static String issue(long userId, long assetId, long exp, byte[] secret) {
        String json = "{\"u\": " + userId + ", \"a\": " + assetId + ", \"exp\": " + exp + "}";
        String payload = Base64.getUrlEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return payload + "." + hmacSha256Hex(secret, payload);
    }

    public static boolean valid(String token, long assetId, byte[] secret, long nowEpochSeconds) {
        if (token == null) return false;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) return false;
        String payload = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!constantTimeEquals(sig, hmacSha256Hex(secret, payload))) return false;
        String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        Long exp = readLong(json, "exp");
        Long asset = readLong(json, "a");
        return exp != null && asset != null && asset == assetId && exp >= nowEpochSeconds;
    }

    private static Long readLong(String json, String key) {
        String needle = "\"" + key + "\": ";
        int at = json.indexOf(needle);
        if (at < 0) return null;
        int start = at + needle.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == start) return null;
        return Long.parseLong(json.substring(start, end));
    }

    private static String hmacSha256Hex(byte[] secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
