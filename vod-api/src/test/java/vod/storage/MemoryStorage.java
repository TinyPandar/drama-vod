package vod.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存存储。Phase 6 起也模拟 S3 Multipart：分片只记大小和 etag，不存字节；
 * 完成时按 S3 规则校验（part 升序、etag 一致、非末块不小于 5 MiB），能取消、能列出。
 */
@Component
@Profile("test")
public class MemoryStorage implements ObjectStorage {
    /** 与 S3 相同：除最后一块外每块至少 5 MiB。 */
    public static final long MIN_PART_BYTES = 5L * 1024 * 1024;

    private final Map<String, ObjectHead> objects = new ConcurrentHashMap<>();
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    /** 被取消过的 upload id（测试断言「存储侧确实 abort 了」）。 */
    public final Set<String> aborted = ConcurrentHashMap.newKeySet();
    /** 置为 true 时 abortMultipart 抛异常，模拟存储不可用。 */
    public volatile boolean failAbort;

    public void put(String bucket, String key) {
        put(bucket, key, 128, "video/mp4");
    }

    public void put(String bucket, String key, long size, String contentType) {
        objects.put(bucket + "/" + key, new ObjectHead(size, "etag-" + size, contentType, null));
    }

    public void clear() {
        objects.clear();
        uploads.clear();
        aborted.clear();
        failAbort = false;
    }

    @Override
    public Optional<ObjectHead> head(String bucket, String key) {
        return Optional.ofNullable(objects.get(bucket + "/" + key));
    }

    @Override
    public String presignPut(String bucket, String key, int expiresSeconds) {
        return "http://upload.local/" + bucket + "/" + key + "?exp=" + expiresSeconds;
    }

    // ---- Multipart ----

    @Override
    public String createMultipart(String bucket, String key, String contentType) {
        String id = "mpu-" + UUID.randomUUID();
        uploads.put(id, new Upload(bucket, key, contentType));
        return id;
    }

    @Override
    public String presignUploadPart(String bucket, String key, String uploadId, int partNumber, int expiresSeconds) {
        return "http://upload.local/" + bucket + "/" + key + "?uploadId=" + uploadId + "&partNumber=" + partNumber
                + "&exp=" + expiresSeconds;
    }

    /** 测试模拟客户端 PUT 一个分片，返回存储给的 etag（重传同一 part 号会覆盖，etag 变化）。 */
    public String uploadPart(String uploadId, int partNumber, long size) {
        Upload u = uploads.get(uploadId);
        if (u == null) throw new NoSuchUploadException("no such upload");
        String etag = "p" + partNumber + "-" + size + "-" + UUID.randomUUID().toString().substring(0, 8);
        synchronized (u) {
            u.parts.put(partNumber, new MultipartPart(partNumber, etag, size));
        }
        return etag;
    }

    public boolean uploadOpen(String uploadId) {
        return uploads.containsKey(uploadId);
    }

    public int openUploads() {
        return uploads.size();
    }

    @Override
    public List<MultipartPart> listParts(String bucket, String key, String uploadId) {
        Upload u = existing(bucket, key, uploadId);
        synchronized (u) {
            return new ArrayList<>(u.parts.values());
        }
    }

    @Override
    public void completeMultipart(String bucket, String key, String uploadId, List<MultipartPart> parts) {
        Upload u = existing(bucket, key, uploadId);
        synchronized (u) {
            if (parts.isEmpty()) throw new MultipartRejectedException("MalformedXML", "no parts");
            long total = 0;
            int previous = 0;
            for (int i = 0; i < parts.size(); i++) {
                MultipartPart p = parts.get(i);
                if (p.partNumber() <= previous) throw new MultipartRejectedException("InvalidPartOrder", "order");
                previous = p.partNumber();
                MultipartPart stored = u.parts.get(p.partNumber());
                if (stored == null || !stored.etag().equals(ObjectStorage.unquote(p.etag()))) {
                    throw new MultipartRejectedException("InvalidPart", "part " + p.partNumber());
                }
                if (i < parts.size() - 1 && stored.size() < MIN_PART_BYTES) {
                    throw new MultipartRejectedException("EntityTooSmall", "part " + p.partNumber());
                }
                total += stored.size();
            }
            objects.put(bucket + "/" + key,
                    new ObjectHead(total, "mpu-" + parts.size(), u.contentType == null ? "binary/octet-stream" : u.contentType, null));
            uploads.remove(uploadId);
        }
    }

    @Override
    public void abortMultipart(String bucket, String key, String uploadId) {
        if (failAbort) throw new IllegalStateException("storage unavailable");
        if (uploads.remove(uploadId) != null) aborted.add(uploadId);
    }

    private Upload existing(String bucket, String key, String uploadId) {
        Upload u = uploads.get(uploadId);
        if (u == null || !u.bucket.equals(bucket) || !u.key.equals(key)) throw new NoSuchUploadException("no such upload");
        return u;
    }

    private static final class Upload {
        final String bucket;
        final String key;
        final String contentType;
        final TreeMap<Integer, MultipartPart> parts = new TreeMap<>();

        Upload(String bucket, String key, String contentType) {
            this.bucket = bucket;
            this.key = key;
            this.contentType = contentType;
        }
    }
}
