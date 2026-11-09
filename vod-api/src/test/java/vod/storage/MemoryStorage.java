package vod.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("test")
public class MemoryStorage implements ObjectStorage {
    private final Map<String, ObjectHead> objects = new ConcurrentHashMap<>();

    public void put(String bucket, String key) {
        put(bucket, key, 128, "video/mp4");
    }

    public void put(String bucket, String key, long size, String contentType) {
        objects.put(bucket + "/" + key, new ObjectHead(size, "etag-" + size, contentType, null));
    }

    @Override
    public Optional<ObjectHead> head(String bucket, String key) {
        return Optional.ofNullable(objects.get(bucket + "/" + key));
    }

    @Override
    public String presignPut(String bucket, String key, int expiresSeconds) {
        return "http://upload.local/" + bucket + "/" + key + "?exp=" + expiresSeconds;
    }
}
