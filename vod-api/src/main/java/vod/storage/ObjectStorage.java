package vod.storage;

import java.util.Optional;

public interface ObjectStorage {
    /** 对象不存在时返回 empty。 */
    Optional<ObjectHead> head(String bucket, String key);

    String presignPut(String bucket, String key, int expiresSeconds);
}
