package vod.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 生产用的 DNS 与投递。投递时 TCP 连接到 {@link WebhookTargetPolicy} 刚检查过的 IP（钉住地址），
 * TLS 握手用原主机名做 SNI 和证书主机名校验（endpoint identification = HTTPS），HTTP 的 Host 头也是原主机名。
 * 全程不按主机名再解析一次，所以检查和连接之间 DNS 被改成内网也连不过去（DNS 重绑定）。
 *
 * <p>只发一次 HTTP/1.1 POST，Connection: close，不跟随重定向（3xx 记失败）；响应只读状态行，正文最多读
 * vod.webhook.max-response-bytes 字节就断开。连接、读取超时都是配置项。
 */
@Component
@Profile("!test")
public class LiveWebhookIo implements WebhookService.DnsLookup, WebhookService.WebhookPoster {
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxResponseBytes;
    private final SSLSocketFactory tls;

    @org.springframework.beans.factory.annotation.Autowired
    public LiveWebhookIo(@Value("${vod.webhook.connect-timeout-ms:3000}") int connectTimeoutMs,
                         @Value("${vod.webhook.read-timeout-ms:5000}") int readTimeoutMs,
                         @Value("${vod.webhook.max-response-bytes:4096}") int maxResponseBytes) {
        this(connectTimeoutMs, readTimeoutMs, maxResponseBytes, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    /** 测试可注入信任自签证书的 TLS 工厂。 */
    public LiveWebhookIo(int connectTimeoutMs, int readTimeoutMs, int maxResponseBytes, SSLSocketFactory tls) {
        this.connectTimeoutMs = Math.max(100, connectTimeoutMs);
        this.readTimeoutMs = Math.max(100, readTimeoutMs);
        this.maxResponseBytes = Math.max(64, maxResponseBytes);
        this.tls = tls;
    }

    @Override
    public List<InetAddress> lookup(String host) {
        try {
            return Arrays.asList(InetAddress.getAllByName(host));
        } catch (Exception e) {
            throw new IllegalArgumentException("unresolved host");
        }
    }

    @Override
    public WebhookService.PostResult post(WebhookTargetPolicy.Target target, InetAddress address, String body,
                                          Map<String, String> headers) {
        long deadline = System.currentTimeMillis() + connectTimeoutMs + (long) readTimeoutMs;
        Socket raw = new Socket();
        try (raw) {
            raw.connect(new InetSocketAddress(address, target.port()), connectTimeoutMs);
            raw.setSoTimeout(readTimeoutMs);
            Socket socket = raw;
            if (target.https()) {
                SSLSocket ssl = (SSLSocket) tls.createSocket(raw, target.host(), target.port(), true);
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isIpLiteral(target.host())) params.setServerNames(List.of(new SNIHostName(target.host())));
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                socket = ssl;
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append("POST ").append(requestTarget(target.uri())).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(hostHeader(target)).append("\r\n");
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(payload.length).append("\r\n");
            head.append("User-Agent: vod-api-webhook/1\r\n");
            head.append("Connection: close\r\n");
            for (var h : headers.entrySet()) {
                if (h.getKey().indexOf('\r') >= 0 || h.getKey().indexOf('\n') >= 0
                        || h.getValue().indexOf('\r') >= 0 || h.getValue().indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("bad header");
                }
                head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            return new WebhookService.PostResult(readStatus(socket.getInputStream(), deadline), null);
        } catch (Exception e) {
            return new WebhookService.PostResult(0, e.getClass().getSimpleName());
        }
    }

    /** 读状态行，再最多读 maxResponseBytes 字节后返回（不关心正文）。超过总时限就放弃。 */
    private int readStatus(InputStream in, long deadline) throws java.io.IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int total = 0, b;
        while ((b = in.read()) != -1) {
            if (++total > maxResponseBytes || System.currentTimeMillis() > deadline) break;
            if (b == '\n') break;
            line.write(b);
        }
        String status = line.toString(StandardCharsets.US_ASCII).trim();
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new java.io.IOException("bad status line");
        int code = Integer.parseInt(parts[1]);
        byte[] buf = new byte[512];
        int n;
        while (total < maxResponseBytes && System.currentTimeMillis() <= deadline
                && (n = in.read(buf, 0, Math.min(buf.length, maxResponseBytes - total))) > 0) {
            total += n;
        }
        return code;
    }

    static String requestTarget(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    static String hostHeader(WebhookTargetPolicy.Target target) {
        String host = target.host().indexOf(':') >= 0 ? "[" + target.host() + "]" : target.host();
        boolean defaultPort = target.port() == (target.https() ? 443 : 80);
        return defaultPort ? host : host + ":" + target.port();
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }
}
