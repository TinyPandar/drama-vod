package vod.upload;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 先迁到 V4 写入旧数据，再迁到最新，核对 V5+ 的回填结果。 */
class StorageObjectBackfillTest {
    @Test
    void legacyAssetsAreBackfilledIntoStorageObjects() {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:backfill;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("4").load().migrate();

        jdbc.update("INSERT INTO vod_tenant(id, name) VALUES (1, 'A'), (2, 'B')");
        jdbc.update("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes) VALUES (1, 'h', '*')");
        jdbc.update("INSERT INTO vod_media_asset(id, tenant_id, status, source_bucket, source_key, source_size) VALUES "
                + "(10, 1, 'READY', 'vod-source', 'raw/t1/10/source.mp4', 300), "
                + "(11, 1, 'UPLOADING', 'vod-source', 'raw/t1/11/source.mp4', NULL), "
                + "(12, 2, 'UPLOADED', 'vod-source', 'raw/t2/12/source.mp4', 50)");
        jdbc.update("INSERT INTO vod_upload_session(id, tenant_id, asset_id, status) VALUES (5, 1, 11, 'OPEN')");

        Instant before = Instant.now();
        Flyway.configure().dataSource(ds).load().migrate();

        for (long assetId : new long[]{10, 11, 12}) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT a.tenant_id, a.source_bucket, a.source_key, a.source_size, "
                            + "o.tenant_id AS o_tenant, o.bucket, o.object_key, o.size, o.role "
                            + "FROM vod_media_asset a JOIN vod_storage_object o ON o.id = a.source_object_id WHERE a.id = ?",
                    assetId);
            assertEquals(row.get("tenant_id"), row.get("o_tenant"));
            assertEquals(row.get("source_bucket"), row.get("bucket"));
            assertEquals(row.get("source_key"), row.get("object_key"));
            assertEquals(row.get("source_size"), row.get("size"));
            assertEquals("SOURCE", row.get("role"));
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM vod_storage_object", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vod_media_asset WHERE source_object_id IS NULL", Integer.class));

        Map<String, Object> session = jdbc.queryForMap("SELECT object_key, expires_at, checksum FROM vod_upload_session WHERE id = 5");
        assertEquals("raw/t1/11/source.mp4", session.get("object_key"));
        assertNotNull(session.get("expires_at"));
        // 旧会话按迁移时刻视为到期。
        assertTrue(!((Timestamp) session.get("expires_at")).toInstant().isAfter(Instant.now()));
        assertTrue(!((Timestamp) session.get("expires_at")).toInstant().isBefore(before.minusSeconds(1)));
        assertNull(session.get("checksum"));

        assertNull(jdbc.queryForObject("SELECT expires_at FROM vod_api_credential", Timestamp.class));
        assertEquals(300L, jdbc.queryForObject("SELECT storage_used_bytes FROM vod_tenant WHERE id = 1", Long.class));
        assertEquals(50L, jdbc.queryForObject("SELECT storage_used_bytes FROM vod_tenant WHERE id = 2", Long.class));
        assertNull(jdbc.queryForObject("SELECT storage_quota_bytes FROM vod_tenant WHERE id = 1", Long.class));
    }
}
