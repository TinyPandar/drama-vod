package vod.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vod.storage.ObjectHead;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** 上传会话时长与源对象校验规则，全部来自配置；默认值只是开发默认，不是容量承诺。 */
@Component
public class UploadPolicy {
    private final int sessionTtlSeconds;
    private final long maxObjectBytes;
    private final Set<String> extensions;
    private final Set<String> contentTypes;

    public UploadPolicy(
            @Value("${vod.upload.session-ttl-seconds:900}") int sessionTtlSeconds,
            @Value("${vod.upload.max-object-bytes:0}") long maxObjectBytes,
            @Value("${vod.upload.allowed-extensions:mp4,mov,m4v,mkv,webm}") String extensions,
            @Value("${vod.upload.allowed-content-types:video/mp4,video/quicktime,video/x-m4v,video/x-matroska,video/webm,application/octet-stream,binary/octet-stream}")
            String contentTypes) {
        if (sessionTtlSeconds <= 0) throw new IllegalStateException("vod.upload.session-ttl-seconds must be > 0");
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.maxObjectBytes = maxObjectBytes;
        this.extensions = csv(extensions);
        this.contentTypes = csv(contentTypes);
    }

    public int sessionTtlSeconds() {
        return sessionTtlSeconds;
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
