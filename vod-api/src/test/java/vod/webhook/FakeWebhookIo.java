package vod.webhook;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 测试用 DNS 与投递：不联网。lookups 按调用顺序依次给出解析结果（用完后一直用最后一个），
 * 便于模拟 DNS 重绑定；posts 记录每次投递真正「连接」的地址、主机名、正文和头。
 */
@Component
@Profile("test")
public class FakeWebhookIo implements WebhookService.DnsLookup, WebhookService.WebhookPoster {
    public record Post(String host, int port, InetAddress address, String body, Map<String, String> headers) {}

    public final List<String> signatures = new CopyOnWriteArrayList<>();
    public final List<Post> posts = new CopyOnWriteArrayList<>();
    public final List<String> lookedUp = new CopyOnWriteArrayList<>();
    /** 默认解析到一个公网地址（文档段以外），这样登记 https://hooks.example.com 能过。 */
    public volatile List<List<InetAddress>> lookups = new ArrayList<>(List.of(List.of(addr("93.184.215.14"))));
    public volatile Function<Post, Integer> responder = p -> 204;

    public void reset() {
        signatures.clear();
        posts.clear();
        lookedUp.clear();
        lookups = new ArrayList<>(List.of(List.of(addr("93.184.215.14"))));
        responder = p -> 204;
    }

    public static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public synchronized List<InetAddress> lookup(String host) {
        lookedUp.add(host);
        List<InetAddress> out = lookups.size() > 1 ? lookups.remove(0) : lookups.get(0);
        return out;
    }

    @Override
    public WebhookService.PostResult post(WebhookTargetPolicy.Target target, InetAddress address, String body,
                                          Map<String, String> headers) {
        Post p = new Post(target.host(), target.port(), address, body, Map.copyOf(headers));
        posts.add(p);
        signatures.add(headers.get("X-Vod-Signature"));
        return new WebhookService.PostResult(responder.apply(p), null);
    }
}
