package vod.webhook;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 socket 上验证「钉住地址」：URL 里的主机名根本解析不了（.invalid），请求仍然到达我们给的 IP，
 * 说明连接用的是检查过的地址而不是重新解析主机名；HTTPS 用原主机名做证书校验，主机名对不上就握手失败。
 */
class PinnedPosterTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test
    void httpGoesToPinnedAddressWithOriginalHostHeader() throws Exception {
        AtomicReference<String> host = new AtomicReference<>(), sig = new AtomicReference<>(), body = new AtomicReference<>(),
                query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.createContext("/hook", ex -> {
            host.set(ex.getRequestHeaders().getFirst("Host"));
            sig.set(ex.getRequestHeaders().getFirst("X-Vod-Signature"));
            query.set(ex.getRequestURI().getRawQuery());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var target = new WebhookTargetPolicy.Target(URI.create("http://rebind.invalid:" + port + "/hook?a=1"),
                    "rebind.invalid", port, false, List.of(LOOPBACK));
            var io = new LiveWebhookIo(2000, 2000, 1024, null);
            var result = io.post(target, LOOPBACK, "{\"x\":1}", Map.of("X-Vod-Signature", "sha256=ab"));
            assertEquals(202, result.status(), String.valueOf(result.error()));
            assertEquals("rebind.invalid:" + port, host.get());
            assertEquals("sha256=ab", sig.get());
            assertEquals("a=1", query.get());
            assertEquals("{\"x\":1}", body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void headerInjectionIsRefused() {
        var target = new WebhookTargetPolicy.Target(URI.create("http://x.invalid:9/h"), "x.invalid", 9, false, List.of(LOOPBACK));
        var result = new LiveWebhookIo(200, 200, 1024, null).post(target, LOOPBACK, "{}", Map.of("X-A", "v\r\nX-B: y"));
        assertEquals(0, result.status());
    }

    @Test
    void httpsVerifiesCertificateAgainstOriginalHostname(@TempDir Path dir) throws Exception {
        Path ks = dir.resolve("hook.p12");
        String pass = "test-only-keystore";
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "hook", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=hooks.test",
                "-ext", "SAN=dns:hooks.test", "-validity", "2", "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", pass, "-keypass", pass).redirectErrorStream(true).start();
        assertEquals(0, p.waitFor(), new String(p.getInputStream().readAllBytes()));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(ks.toFile())) {
            store.load(in, pass.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, pass.toCharArray());
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, tmf.getTrustManagers(), null);

        AtomicReference<String> host = new AtomicReference<>();
        HttpsServer server = HttpsServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverCtx));
        server.createContext("/", ex -> {
            host.set(ex.getRequestHeaders().getFirst("Host"));
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var io = new LiveWebhookIo(2000, 2000, 1024, clientCtx.getSocketFactory());
            var ok = io.post(new WebhookTargetPolicy.Target(URI.create("https://hooks.test:" + port + "/"), "hooks.test", port, true,
                    List.of(LOOPBACK)), LOOPBACK, "{}", Map.of());
            assertEquals(204, ok.status(), String.valueOf(ok.error()));
            assertEquals("hooks.test:" + port, host.get());
            var wrong = io.post(new WebhookTargetPolicy.Target(URI.create("https://evil.test:" + port + "/"), "evil.test", port, true,
                    List.of(LOOPBACK)), LOOPBACK, "{}", Map.of());
            assertEquals(0, wrong.status());
            assertNotNull(wrong.error());
            assertTrue(wrong.error().contains("SSL"), wrong.error());
        } finally {
            server.stop(0);
        }
    }
}
