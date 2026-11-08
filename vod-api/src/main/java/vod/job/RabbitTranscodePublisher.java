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
                ConnectionFactory factory = new ConnectionFactory();
                factory.setUri(url);
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
                return channel.waitForConfirms(CONFIRM_TIMEOUT_MILLIS);
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() throws Exception {
        if (connection != null) connection.close();
    }
}
