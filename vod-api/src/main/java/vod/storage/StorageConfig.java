package vod.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class StorageConfig {
    @Bean
    @Profile("!test")
    ObjectStorage objectStorage(
            @Value("${vod.s3.endpoint:}") String endpoint,
            @Value("${vod.s3.public-endpoint:}") String publicEndpoint,
            @Value("${vod.s3.access-key:}") String accessKey,
            @Value("${vod.s3.secret-key:}") String secretKey) {
        if (endpoint == null || endpoint.isBlank()) return new UnavailableStorage();
        String publicUrl = publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        return new S3ObjectStorage(endpoint, publicUrl, accessKey, secretKey);
    }
}
