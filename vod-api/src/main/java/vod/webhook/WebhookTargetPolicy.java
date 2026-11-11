package vod.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vod.security.SecretGuard;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登记和每次投递共用的目标检查：URL 形状 → 解析一次 → 所有地址都必须是公网。返回的 {@link Target} 带着这次检查过的地址，
 * 投递只连这些地址（{@link LiveWebhookIo}），不让运行时再按主机名解析。
 *
 * <p>开发开关（默认都关，只在 VOD_ENV=dev 生效；非 dev 下设置了就拒绝启动）：
 * vod.webhook.dev-allow-http 放行 http://；vod.webhook.dev-allow-private-hosts 列出的主机名可以解析到内网/回环
 * （例如 host.docker.internal，给 scripts/platform_live.py 在本机起接收端），链路本地与 metadata 仍然拒绝。
 */
@Component
public class WebhookTargetPolicy {
    private final WebhookService.DnsLookup dns;
    private final boolean allowHttp;
    private final Set<String> devHosts;

    public WebhookTargetPolicy(SecretGuard secrets, WebhookService.DnsLookup dns,
                               @Value("${vod.webhook.dev-allow-http:false}") boolean allowHttp,
                               @Value("${vod.webhook.dev-allow-private-hosts:}") String devHosts) {
        this.dns = dns;
        Set<String> hosts = Arrays.stream(devHosts == null ? new String[0] : devHosts.split(","))
                .map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (!secrets.dev() && (allowHttp || !hosts.isEmpty())) {
            throw new IllegalStateException("vod.webhook.dev-allow-http / dev-allow-private-hosts are only allowed with VOD_ENV=dev");
        }
        this.allowHttp = allowHttp;
        this.devHosts = hosts;
    }

    public record Target(URI uri, String host, int port, boolean https, List<InetAddress> addresses) {}

    /** 只看 URL 形状（不解析）。 */
    public URI checkUrl(String raw) {
        return WebhookUrlGuard.requireUrl(raw, allowHttp, devHosts);
    }

    /** 形状 + 解析 + 地址检查。任何一步不过抛 IllegalArgumentException（登记时 400，投递时记一次失败）。 */
    public Target resolve(String raw) {
        URI uri = checkUrl(raw);
        String host = WebhookUrlGuard.host(uri);
        List<InetAddress> addresses = dns.lookup(host);
        if (devHosts.contains(host)) {
            WebhookUrlGuard.requireResolvedDevAllowed(addresses);
        } else {
            WebhookUrlGuard.requireResolvedPublic(addresses);
        }
        boolean https = uri.getScheme().equalsIgnoreCase("https");
        int port = uri.getPort() > 0 ? uri.getPort() : (https ? 443 : 80);
        return new Target(uri, host, port, https, List.copyOf(addresses));
    }
}
