package vod.storage;

public interface ObjectStorage {
    boolean exists(String bucket, String key);

    long size(String bucket, String key);

    String presignPut(String bucket, String key, int expiresSeconds);
}
