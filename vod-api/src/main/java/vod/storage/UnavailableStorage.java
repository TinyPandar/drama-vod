package vod.storage;

import java.util.List;
import java.util.Optional;

public class UnavailableStorage implements ObjectStorage {
    @Override
    public Optional<ObjectHead> head(String bucket, String key) {
        throw notConfigured();
    }

    @Override
    public String presignPut(String bucket, String key, int expiresSeconds) {
        throw notConfigured();
    }

    @Override
    public String createMultipart(String bucket, String key, String contentType) {
        throw notConfigured();
    }

    @Override
    public String presignUploadPart(String bucket, String key, String uploadId, int partNumber, int expiresSeconds) {
        throw notConfigured();
    }

    @Override
    public List<MultipartPart> listParts(String bucket, String key, String uploadId) {
        throw notConfigured();
    }

    @Override
    public void completeMultipart(String bucket, String key, String uploadId, List<MultipartPart> parts) {
        throw notConfigured();
    }

    @Override
    public void abortMultipart(String bucket, String key, String uploadId) {
        throw notConfigured();
    }

    @Override
    public void delete(String bucket, String key) {
        throw notConfigured();
    }

    private static IllegalStateException notConfigured() {
        return new IllegalStateException("object storage is not configured");
    }
}
