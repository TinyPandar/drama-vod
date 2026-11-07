package vod.job;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("test")
public class RecordingPublisher implements TranscodePublisher {
    public final List<String> messages = new ArrayList<>();

    @Override
    public boolean publish(String payload) {
        messages.add(payload);
        return true;
    }
}
