package vod.job;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Profile("!test")
public class RabbitTranscodePublisher implements TranscodePublisher, AutoCloseable {
    private static final long CONFIRM_TIMEOUT_MILLIS = 5000;
    private static final int CONFIRM_TIMEOUT_MILLIS_INT = (int) CONFIRM_TIMEOUT_MILLIS;
    private final String url;
    private Connection connection;

    public RabbitTranscodePublisher(@Value("${vod.amqp-url:}") String url) {
        this.url = url == null ? "" : url;
    }

    @Override
    public synchronized boolean publish(String payload) {
        if (url.isBlank()) return false;
        try {
            if (connection == null || !connection.isOpen()) {
                closeQuietly();
                ConnectionFactory factory = new ConnectionFactory();
                factory.setUri(url);
                // 不用客户端自带的自动恢复：它会按记录重放每个 channel 上声明过的队列，而这些 channel 早已关闭，
                // RabbitMQ 重启后恢复失败、isOpen() 却可能仍为 true，发布就一直失败。失败时由这里丢弃连接、下次重连。
                factory.setAutomaticRecoveryEnabled(false);
                factory.setTopologyRecoveryEnabled(false);
                factory.setConnectionTimeout(CONFIRM_TIMEOUT_MILLIS_INT);
                connection = factory.newConnection();
            }
            try (Channel channel = connection.createChannel()) {
                channel.confirmSelect();
                channel.queueDeclare("transcode", true, false, false, null);
                channel.basicPublish("", "transcode",
                        new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                                .deliveryMode(2)
                                .contentType("application/json")
                                .build(),
                        payload.getBytes(StandardCharsets.UTF_8));
                boolean confirmed = channel.waitForConfirms(CONFIRM_TIMEOUT_MILLIS);
                if (!confirmed) closeQuietly();
                return confirmed;
            }
        } catch (Exception e) {
            closeQuietly();
            return false;
        }
    }

    private void closeQuietly() {
        Connection c = connection;
        connection = null;
        if (c == null) return;
        try {
            c.abort((int) CONFIRM_TIMEOUT_MILLIS);
        } catch (Exception ignored) {
            // 连接已断，丢弃即可
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }
}
