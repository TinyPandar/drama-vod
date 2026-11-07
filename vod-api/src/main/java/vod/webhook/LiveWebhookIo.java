package vod.webhook;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Component
@Profile("!test")
public class LiveWebhookIo implements WebhookService.DnsLookup, WebhookService.WebhookPoster {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Override
    public List<InetAddress> lookup(String host) {
        try {
            return Arrays.asList(InetAddress.getAllByName(host));
        } catch (Exception e) {
            throw new IllegalArgumentException("unresolved host");
        }
    }

    @Override
    public int post(String url, String body, String signature) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Vod-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return 0;
        }
    }
}
