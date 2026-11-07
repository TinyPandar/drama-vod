package vod.webhook;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("test")
public class FakeWebhookIo implements WebhookService.DnsLookup, WebhookService.WebhookPoster {
    public final List<String> signatures = new ArrayList<>();
    public List<InetAddress> addresses = List.of(InetAddress.getLoopbackAddress());

    @Override
    public List<InetAddress> lookup(String host) {
        return addresses;
    }

    @Override
    public int post(String url, String body, String signature) {
        signatures.add(signature);
        return 204;
    }
}
