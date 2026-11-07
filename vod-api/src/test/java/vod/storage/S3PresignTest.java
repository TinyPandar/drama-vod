package vod.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3PresignTest {
    @Test
    void putUrlPointsAtStorageNotTheApi() {
        try (var storage = new S3ObjectStorage(
                "http://minio:9000", "http://localhost:9000", "minioadmin", "minioadmin123")) {
            String url = storage.presignPut("vod-source", "raw/t1/9/source.mp4", 900);
            assertTrue(url.startsWith("http://localhost:9000/"));
            assertTrue(url.contains("vod-source"));
            assertTrue(url.contains("raw/t1/9/source.mp4") || url.contains("raw%2Ft1%2F9%2Fsource.mp4"));
            assertTrue(url.contains("X-Amz-Algorithm") || url.contains("X-Amz-Signature"));
            assertFalse(url.contains("/api/"));
        }
    }
}
