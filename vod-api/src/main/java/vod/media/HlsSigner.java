package vod.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** 与 api/main.py 的 hls_dir、Nginx secure_link_md5 使用同一条算法。 */
public final class HlsSigner {
    private HlsSigner() {}

    public static String signature(long expires, long assetId, String secret) {
        return signRaw(expires + "/" + assetId + " " + secret);
    }

    /** 核心资产用 /hls/v1/，明文多一段 v1，避免和旧资产 id 的签名混用。 */
    public static String v1Signature(long expires, long assetId, String secret) {
        return signRaw(expires + "/v1/" + assetId + " " + secret);
    }

    public static String v1MasterUrl(long expires, long assetId, String secret) {
        return "/hls/v1/" + v1Signature(expires, assetId, secret) + "/" + expires + "/" + assetId + "/master.m3u8";
    }

    private static String signRaw(String raw) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String masterUrl(long expires, long assetId, String secret) {
        return "/hls/" + signature(expires, assetId, secret) + "/" + expires + "/" + assetId + "/master.m3u8";
    }
}
