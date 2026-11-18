package vod.storage;

import java.util.List;
import java.util.Optional;

public interface ObjectStorage {
    /** 对象不存在时返回 empty。 */
    Optional<ObjectHead> head(String bucket, String key);

    String presignPut(String bucket, String key, int expiresSeconds);

    // ---- Phase 6：Multipart。创建/列出/完成/取消走内网，分片预签名走公网（和 presignPut 分工一致）。----

    /** 在 bucket/key 上创建分片上传，返回存储侧的 upload id。contentType 写进最终对象，complete 时 HEAD 会读到。 */
    String createMultipart(String bucket, String key, String contentType);

    /** 给某个 part（1..10000）签一个预签名 PUT。 */
    String presignUploadPart(String bucket, String key, String uploadId, int partNumber, int expiresSeconds);

    /** 存储侧已经收到的 part，按 part 号升序。upload 不存在（已完成或已取消）抛 {@link NoSuchUploadException}。 */
    List<MultipartPart> listParts(String bucket, String key, String uploadId);

    /**
     * 按客户端上报的 part 号与 etag 组合成一个对象。etag 对不上、缺 part、顺序不对、非末块太小：
     * {@link MultipartRejectedException}；upload 不存在：{@link NoSuchUploadException}。
     */
    void completeMultipart(String bucket, String key, String uploadId, List<MultipartPart> parts);

    /** 取消分片上传，丢掉已传分片。upload 已不存在时视为成功（幂等）。 */
    void abortMultipart(String bucket, String key, String uploadId);

    /** 路线 B：删除对象。对象不存在视为成功（幂等）。 */
    void delete(String bucket, String key);

    /** 一个分片：complete 时 size 不参与（填 0 即可）；listParts 返回存储侧的 size。etag 不带引号。 */
    record MultipartPart(int partNumber, String etag, long size) {
    }

    class NoSuchUploadException extends RuntimeException {
        public NoSuchUploadException(String message) {
            super(message);
        }
    }

    /** 存储拒绝组合（InvalidPart / InvalidPartOrder / EntityTooSmall 等）。code 是存储返回的错误码，只用于提示。 */
    class MultipartRejectedException extends RuntimeException {
        private final String code;

        public MultipartRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** etag 统一去掉首尾引号。 */
    static String unquote(String etag) {
        if (etag == null) return null;
        String t = etag.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }
}
