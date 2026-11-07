package vod.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.util.Collection;

/** 拒绝内网、回环和云 metadata 地址。交付前还要用解析后的地址再查一次，降低 DNS 重绑定风险。 */
public final class WebhookUrlGuard {
    private WebhookUrlGuard() {}

    public static URI requirePublicHttps(String raw) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad url");
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("https required");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("userinfo not allowed");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("metadata.google.internal")) {
            throw new IllegalArgumentException("private host");
        }
        if (isPrivateLiteral(lower)) throw new IllegalArgumentException("private host");
        return uri;
    }

    public static void requireResolvedPublic(Collection<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) throw new IllegalArgumentException("unresolved host");
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                throw new IllegalArgumentException("private address");
            }
        }
    }

    static boolean isPrivateLiteral(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                n[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (n[i] < 0 || n[i] > 255) return true;
        }
        if (n[0] == 0 || n[0] == 10 || n[0] == 127) return true;
        if (n[0] == 169 && n[1] == 254) return true;
        if (n[0] == 192 && n[1] == 168) return true;
        return n[0] == 172 && n[1] >= 16 && n[1] <= 31;
    }
}
