package vod.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

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
    public boolean exists(String bucket, String key) {
        try {
            internal.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    @Override
    public long size(String bucket, String key) {
        return internal.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength();
    }

    @Override
    public String presignPut(String bucket, String key, int expiresSeconds) {
        var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expiresSeconds))
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                .build());
        return signed.url().toString();
    }

    @Override
    public void close() {
        internal.close();
        presigner.close();
    }
}
