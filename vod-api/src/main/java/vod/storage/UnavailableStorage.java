package vod.storage;

import java.util.Optional;

public class UnavailableStorage implements ObjectStorage {
    @Override
    public Optional<ObjectHead> head(String bucket, String key) {
        throw new IllegalStateException("object storage is not configured");
    }

    @Override
    public String presignPut(String bucket, String key, int expiresSeconds) {
        throw new IllegalStateException("object storage is not configured");
    }
}
