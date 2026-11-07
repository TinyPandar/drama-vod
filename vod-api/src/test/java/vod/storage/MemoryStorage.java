package vod.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("test")
public class MemoryStorage implements ObjectStorage {
    private final Set<String> objects = ConcurrentHashMap.newKeySet();

    public void put(String bucket, String key) {
        objects.add(bucket + "/" + key);
    }

    @Override
    public boolean exists(String bucket, String key) {
        return objects.contains(bucket + "/" + key);
    }

    @Override
    public long size(String bucket, String key) {
        return exists(bucket, key) ? 128 : 0;
    }

    @Override
    public String presignPut(String bucket, String key, int expiresSeconds) {
        return "http://upload.local/" + bucket + "/" + key + "?exp=" + expiresSeconds;
    }
}
