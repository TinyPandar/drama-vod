package vod.job;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("test")
public class RecordingPublisher implements TranscodePublisher {
    public final List<String> messages = new ArrayList<>();
    /** 测试可切换：默认成功，设为 false 模拟 broker 未确认。 */
    public volatile boolean result = true;

    @Override
    public boolean publish(String payload) {
        messages.add(payload);
        return result;
    }
}
