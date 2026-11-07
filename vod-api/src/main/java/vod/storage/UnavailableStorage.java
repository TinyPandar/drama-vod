package vod.storage;

public class UnavailableStorage implements ObjectStorage {
    @Override
    public boolean exists(String bucket, String key) {
        throw new IllegalStateException("object storage is not configured");
    }

    @Override
    public long size(String bucket, String key) {
        throw new IllegalStateException("object storage is not configured");
    }

    @Override
    public String presignPut(String bucket, String key, int expiresSeconds) {
        throw new IllegalStateException("object storage is not configured");
    }
}
