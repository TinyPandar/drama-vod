package vod.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vod.storage.ObjectHead;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 上传会话时长与源对象校验规则，全部来自配置；默认值只是开发默认，不是容量承诺。 */
@Component
public class UploadPolicy {
    /** S3：除最后一块外每块不小于 5 MiB，单块不大于 5 GiB，最多 10000 块。 */
    public static final long MIN_PART_BYTES = 5L * 1024 * 1024;
    public static final long MAX_PART_BYTES = 5L * 1024 * 1024 * 1024;
    public static final int MAX_PARTS = 10_000;
    /** multipart 会话有效期的硬上限（7 天），配置不能超过。 */
    public static final int MAX_MULTIPART_TTL_SECONDS = 7 * 24 * 3600;
    /** 扩展名 → 创建 multipart 时写进对象的 Content-Type（客户端没声明时用）。 */
    private static final Map<String, String> TYPE_BY_EXT = Map.of(
            "mp4", "video/mp4", "mov", "video/quicktime", "m4v", "video/x-m4v",
            "mkv", "video/x-matroska", "webm", "video/webm");

    private final int sessionTtlSeconds;
    private final long maxObjectBytes;
    private final Set<String> extensions;
    private final Set<String> contentTypes;
    private final long partSizeBytes;
    private final long multipartThresholdBytes;
    private final int multipartSessionTtlSeconds;
    private final int partUrlTtlSeconds;
    private final int maxPartsPerRequest;

    public UploadPolicy(
            @Value("${vod.upload.session-ttl-seconds:900}") int sessionTtlSeconds,
            @Value("${vod.upload.max-object-bytes:0}") long maxObjectBytes,
            @Value("${vod.upload.allowed-extensions:mp4,mov,m4v,mkv,webm}") String extensions,
            @Value("${vod.upload.allowed-content-types:video/mp4,video/quicktime,video/x-m4v,video/x-matroska,video/webm,application/octet-stream,binary/octet-stream}")
            String contentTypes,
            @Value("${vod.upload.part-size-bytes:8388608}") long partSizeBytes,
            @Value("${vod.upload.multipart-threshold-bytes:0}") long multipartThresholdBytes,
            @Value("${vod.upload.multipart-session-ttl-seconds:86400}") int multipartSessionTtlSeconds,
            @Value("${vod.upload.part-url-ttl-seconds:900}") int partUrlTtlSeconds,
            @Value("${vod.upload.max-parts-per-request:100}") int maxPartsPerRequest) {
        if (sessionTtlSeconds <= 0) throw new IllegalStateException("vod.upload.session-ttl-seconds must be > 0");
        if (partSizeBytes < MIN_PART_BYTES || partSizeBytes > MAX_PART_BYTES) {
            throw new IllegalStateException("vod.upload.part-size-bytes must be between 5 MiB and 5 GiB");
        }
        if (maxObjectBytes > 0 && (maxObjectBytes + partSizeBytes - 1) / partSizeBytes > MAX_PARTS) {
            throw new IllegalStateException("vod.upload.max-object-bytes needs more than 10000 parts of part-size-bytes");
        }
        if (multipartThresholdBytes < 0) throw new IllegalStateException("vod.upload.multipart-threshold-bytes must be >= 0");
        if (multipartSessionTtlSeconds <= 0 || multipartSessionTtlSeconds > MAX_MULTIPART_TTL_SECONDS) {
            throw new IllegalStateException("vod.upload.multipart-session-ttl-seconds must be in 1..604800");
        }
        if (partUrlTtlSeconds <= 0) throw new IllegalStateException("vod.upload.part-url-ttl-seconds must be > 0");
        if (maxPartsPerRequest <= 0 || maxPartsPerRequest > MAX_PARTS) {
            throw new IllegalStateException("vod.upload.max-parts-per-request must be in 1..10000");
        }
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.maxObjectBytes = maxObjectBytes;
        this.extensions = csv(extensions);
        this.contentTypes = csv(contentTypes);
        this.partSizeBytes = partSizeBytes;
        this.multipartThresholdBytes = multipartThresholdBytes == 0 ? partSizeBytes : multipartThresholdBytes;
        this.multipartSessionTtlSeconds = multipartSessionTtlSeconds;
        this.partUrlTtlSeconds = partUrlTtlSeconds;
        this.maxPartsPerRequest = maxPartsPerRequest;
    }

    public int sessionTtlSeconds() {
        return sessionTtlSeconds;
    }

    public long maxObjectBytes() {
        return maxObjectBytes;
    }

    public long partSizeBytes() {
        return partSizeBytes;
    }

    public long multipartThresholdBytes() {
        return multipartThresholdBytes;
    }

    public int multipartSessionTtlSeconds() {
        return multipartSessionTtlSeconds;
    }

    public int partUrlTtlSeconds() {
        return partUrlTtlSeconds;
    }

    public int maxPartsPerRequest() {
        return maxPartsPerRequest;
    }

    /**
     * multipart 对象的 Content-Type：浏览器分片 PUT 不能决定最终对象类型，只能在创建时定。
     * 客户端声明了就必须在允许列表里（否则 415）；没声明按扩展名推，推不出来用 application/octet-stream。
     * complete 时仍按 HEAD 结果走 {@link #check}，不信这里。
     */
    public String multipartContentType(String ext, String declared) {
        if (declared != null && !declared.isBlank()) {
            String type = mime(declared);
            if (type == null || !contentTypes.contains(type)) {
                throw new UploadService.RejectedException(415, "unsupported content type");
            }
            return type;
        }
        String guess = TYPE_BY_EXT.get(ext);
        return guess != null && contentTypes.contains(guess) ? guess : "application/octet-stream";
    }

    /** 客户端可选声明文件名，只用来决定对象 key 的扩展名；没给时按 mp4。 */
    public String extensionFor(String filename) {
        String ext = filename == null || filename.isBlank() ? "mp4" : extension(filename);
        if (ext == null || !extensions.contains(ext)) throw new IllegalArgumentException("unsupported file extension");
        return ext;
    }

    /** complete 时按 HEAD 结果校验，不信客户端声明。 */
    public void check(String objectKey, ObjectHead head) {
        String ext = extension(objectKey);
        if (ext == null || !extensions.contains(ext)) {
            throw new UploadService.RejectedException(415, "unsupported file extension");
        }
        if (head.size() <= 0) throw new UploadService.RejectedException(422, "source object is empty");
        if (maxObjectBytes > 0 && head.size() > maxObjectBytes) {
            throw new UploadService.RejectedException(413, "source object exceeds max size");
        }
        String type = mime(head.contentType());
        if (type == null || !contentTypes.contains(type)) {
            throw new UploadService.RejectedException(415, "unsupported content type");
        }
    }

    private static String extension(String name) {
        String base = name.substring(name.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return null;
        return base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String mime(String contentType) {
        if (contentType == null) return null;
        String bare = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return bare.isEmpty() ? null : bare;
    }

    private static Set<String> csv(String value) {
        return Arrays.stream(value.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
