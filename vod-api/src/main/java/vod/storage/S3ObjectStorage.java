package vod.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 预签名用浏览器能访问的地址，核对文件用内网地址。大文件不经过本服务。 */
public final class S3ObjectStorage implements ObjectStorage, AutoCloseable {
    private final S3Client internal;
    private final S3Presigner presigner;

    public S3ObjectStorage(String internalEndpoint, String publicEndpoint, String accessKey, String secretKey) {
        var creds = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.internal = S3Client.builder()
                .endpointOverride(URI.create(internalEndpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .serviceConfiguration(pathStyle)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .serviceConfiguration(pathStyle)
                .build();
    }

    @Override
    public Optional<ObjectHead> head(String bucket, String key) {
        try {
            var r = internal.headObject(HeadObjectRequest.builder().bucket(bucket).key(key)
                    .checksumMode(ChecksumMode.ENABLED).build());
            String checksum = r.checksumSHA256() == null ? null : "sha256:" + r.checksumSHA256();
            long size = r.contentLength() == null ? 0 : r.contentLength();
            return Optional.of(new ObjectHead(size, unquote(r.eTag()), r.contentType(), checksum));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw e;
        }
    }

    private static String unquote(String etag) {
        return ObjectStorage.unquote(etag);
    }

    @Override
    public String presignPut(String bucket, String key, int expiresSeconds) {
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expiresSeconds))
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                .build());
        return signed.url().toString();
    }

    // ---- Phase 6：Multipart（MinIO / S3 标准 API）----

    /** 存储拒绝组合时返回的错误码：客户端可修正后重试 complete，会话保持 OPEN。 */
    private static final Set<String> REJECT_CODES =
            Set.of("InvalidPart", "InvalidPartOrder", "EntityTooSmall", "MalformedXML", "InvalidArgument");

    @Override
    public String createMultipart(String bucket, String key, String contentType) {
        var req = CreateMultipartUploadRequest.builder().bucket(bucket).key(key);
        if (contentType != null && !contentType.isBlank()) req.contentType(contentType);
        return internal.createMultipartUpload(req.build()).uploadId();
    }

    @Override
    public String presignUploadPart(String bucket, String key, String uploadId, int partNumber, int expiresSeconds) {
        var signed = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expiresSeconds))
                .uploadPartRequest(UploadPartRequest.builder().bucket(bucket).key(key)
                        .uploadId(uploadId).partNumber(partNumber).build())
                .build());
        return signed.url().toString();
    }

    @Override
    public List<MultipartPart> listParts(String bucket, String key, String uploadId) {
        var out = new ArrayList<MultipartPart>();
        Integer marker = null;
        try {
            while (true) {
                var req = ListPartsRequest.builder().bucket(bucket).key(key).uploadId(uploadId).maxParts(1000);
                if (marker != null) req.partNumberMarker(marker);
                var page = internal.listParts(req.build());
                for (var p : page.parts()) {
                    out.add(new MultipartPart(p.partNumber(), unquote(p.eTag()), p.size() == null ? 0 : p.size()));
                }
                if (!Boolean.TRUE.equals(page.isTruncated()) || page.nextPartNumberMarker() == null) break;
                marker = page.nextPartNumberMarker();
            }
        } catch (NoSuchUploadException e) {
            throw new ObjectStorage.NoSuchUploadException("multipart upload not found");
        } catch (S3Exception e) {
            if (isNoSuchUpload(e)) throw new ObjectStorage.NoSuchUploadException("multipart upload not found");
            throw e;
        }
        out.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));
        return out;
    }

    @Override
    public void completeMultipart(String bucket, String key, String uploadId, List<MultipartPart> parts) {
        var completed = parts.stream()
                .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag("\"" + unquote(p.etag()) + "\"").build())
                .toList();
        try {
            internal.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());
        } catch (NoSuchUploadException e) {
            throw new ObjectStorage.NoSuchUploadException("multipart upload not found");
        } catch (S3Exception e) {
            if (isNoSuchUpload(e)) throw new ObjectStorage.NoSuchUploadException("multipart upload not found");
            String code = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            if (code != null && REJECT_CODES.contains(code)) {
                throw new MultipartRejectedException(code, "storage rejected multipart completion: " + code);
            }
            throw e;
        }
    }

    @Override
    public void abortMultipart(String bucket, String key, String uploadId) {
        try {
            internal.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId).build());
        } catch (NoSuchUploadException e) {
            // 已完成或已取消：幂等
        } catch (S3Exception e) {
            if (!isNoSuchUpload(e)) throw e;
        }
    }

    private static boolean isNoSuchUpload(S3Exception e) {
        return e.awsErrorDetails() != null && "NoSuchUpload".equals(e.awsErrorDetails().errorCode());
    }

    @Override
    public void close() {
        internal.close();
        presigner.close();
    }
}
