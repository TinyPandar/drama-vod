package vod.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;

/**
 * Webhook 目标地址检查（SSRF）。登记时和每次投递时都要过：URL 形状 → 解析主机名 → 每个地址都必须是公网单播。
 * 只靠「先解析再检查」挡不住 DNS 重绑定，所以投递时连接的是这次检查过的那个 IP（见 {@link LiveWebhookIo}），不再按主机名重新解析。
 *
 * <p>拒绝：IPv4 的 0/8、10/8、100.64/10、127/8、169.254/16（含云 metadata）、172.16/12、192.0.0/24、192.0.2/24、192.168/16、
 * 198.18/15、198.51.100/24、203.0.113/24、组播与保留段；IPv6 只接受 2000::/3 全局单播，并拒绝其中的文档段 2001:db8::/32、
 * Teredo 2001::/32、6to4 2002::/16（内嵌 IPv4 不可信）；回环 ::1、未指定 ::、链路本地 fe80::/10、ULA fc00::/7、组播 ff00::/8
 * 都不在 2000::/3 里，自然被拒；IPv4 映射 ::ffff:a.b.c.d、IPv4 兼容 ::a.b.c.d、NAT64 64:ff9b::/96 按内嵌的 IPv4 判断。
 */
public final class WebhookUrlGuard {
    private WebhookUrlGuard() {}

    /** 只接受 HTTPS（Phase 2 起的行为）。 */
    public static URI requirePublicHttps(String raw) {
        return requireUrl(raw, false);
    }

    public static URI requireUrl(String raw, boolean allowHttp) {
        return requireUrl(raw, allowHttp, java.util.Set.of());
    }

    /**
     * allowHttp 只在 VOD_ENV=dev 且显式打开 vod.webhook.dev-allow-http 时为 true；
     * devAllowedHosts 只在 dev 下由 vod.webhook.dev-allow-private-hosts 给出（默认空），这些主机名跳过主机名与内网字面量检查。
     */
    public static URI requireUrl(String raw, boolean allowHttp, java.util.Set<String> devAllowedHosts) {
        if (raw == null || raw.isBlank() || raw.length() > 500) throw new IllegalArgumentException("bad url");
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("bad url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) {
            throw new IllegalArgumentException("https required");
        }
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("userinfo not allowed");
        if (uri.getRawFragment() != null) throw new IllegalArgumentException("fragment not allowed");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host required");
        if (uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("bad port");
        String lower = host(uri);
        if (devAllowedHosts.contains(lower)) return uri;
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.equals("metadata.google.internal")
                || lower.endsWith(".internal") || lower.endsWith(".local")) {
            throw new IllegalArgumentException("private host");
        }
        if (isPrivateLiteral(lower)) throw new IllegalArgumentException("private host");
        return uri;
    }

    /** URI 里的主机名，小写、去掉 IPv6 字面量的方括号与末尾的点。 */
    public static String host(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }

    public static void requireResolvedPublic(Collection<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) throw new IllegalArgumentException("unresolved host");
        for (InetAddress address : addresses) {
            if (!isPublic(address)) throw new IllegalArgumentException("private address");
        }
    }

    /** dev 放行的主机：允许内网和回环，但链路本地（含 169.254.169.254 metadata、fe80::/10）始终拒绝。 */
    public static void requireResolvedDevAllowed(Collection<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) throw new IllegalArgumentException("unresolved host");
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("private address");
            }
        }
    }

    public static boolean isPublic(InetAddress address) {
        if (address instanceof Inet4Address v4) return isPublicV4(v4.getAddress());
        if (address instanceof Inet6Address v6) return isPublicV6(v6.getAddress());
        return false;
    }

    static boolean isPublicV4(byte[] b) {
        int a0 = b[0] & 0xff, a1 = b[1] & 0xff, a2 = b[2] & 0xff;
        if (a0 == 0 || a0 == 10 || a0 == 127) return false;
        if (a0 == 100 && a1 >= 64 && a1 <= 127) return false;          // 100.64.0.0/10 CGNAT
        if (a0 == 169 && a1 == 254) return false;                        // 链路本地，含 169.254.169.254
        if (a0 == 172 && a1 >= 16 && a1 <= 31) return false;
        if (a0 == 192 && a1 == 168) return false;
        if (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)) return false;  // 192.0.0.0/24、192.0.2.0/24
        if (a0 == 198 && (a1 == 18 || a1 == 19)) return false;           // 198.18.0.0/15
        if (a0 == 198 && a1 == 51 && a2 == 100) return false;
        if (a0 == 203 && a1 == 0 && a2 == 113) return false;
        return a0 < 224;                                                 // 组播 224/4、保留 240/4、广播
    }

    static boolean isPublicV6(byte[] b) {
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) firstTenZero &= b[i] == 0;
        // ::ffff:a.b.c.d（IPv4 映射）。JDK 解析时通常已转成 Inet4Address，这里兜底。
        if (firstTenZero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) return isPublicV4(tail4(b));
        boolean firstTwelveZero = firstTenZero && b[10] == 0 && b[11] == 0;
        // ::a.b.c.d（已废弃的 IPv4 兼容）以及 ::、::1
        if (firstTwelveZero) return isPublicV4(tail4(b));
        // NAT64 64:ff9b::/96
        if (b[0] == 0 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
            boolean zero = true;
            for (int i = 4; i < 12; i++) zero &= b[i] == 0;
            return zero && isPublicV4(tail4(b));
        }
        if ((b[0] & 0xe0) != 0x20) return false;                         // 只接受 2000::/3
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01) {
            if (b[2] == 0x0d && (b[3] & 0xff) == 0xb8) return false;     // 2001:db8::/32 文档段
            if (b[2] == 0 && b[3] == 0) return false;                    // 2001::/32 Teredo
        }
        return !((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02);        // 2002::/16 6to4
    }

    private static byte[] tail4(byte[] b) {
        return new byte[]{b[12], b[13], b[14], b[15]};
    }

    /** URL 里直接写的 IP（IPv4 点分或 IPv6 字面量）是否不是公网。主机名返回 false，交给解析后的检查。 */
    static boolean isPrivateLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            try {
                return !isPublic(InetAddress.getByName(host));  // 含冒号必是 IPv6 字面量，不走 DNS
            } catch (UnknownHostException e) {
                return true;
            }
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v;
            try {
                v = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (v < 0 || v > 255) return true;
            b[i] = (byte) v;
        }
        return !isPublicV4(b);
    }
}
