package vod.usage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

@Service
public class UsageService {
    private final JdbcTemplate jdbc;
    private final long defaultStorageQuotaBytes;

    /** defaultStorageQuotaBytes 用于 vod_tenant.storage_quota_bytes 为 NULL 的租户；<= 0 表示不限制。 */
    public UsageService(JdbcTemplate jdbc,
                        @Value("${vod.quota.default-storage-bytes:0}") long defaultStorageQuotaBytes) {
        this.jdbc = jdbc;
        this.defaultStorageQuotaBytes = defaultStorageQuotaBytes;
    }

    /**
     * 原子占用存储配额：单条条件 UPDATE 同时判断上限并累加，并发 complete 不会一起越过上限。
     * 必须在调用方事务里执行，后续失败回滚时占用一并回滚。
     */
    public void reserveStorage(long tenantId, long bytes) {
        int updated = jdbc.update("UPDATE vod_tenant SET storage_used_bytes = storage_used_bytes + ? "
                        + "WHERE id = ? AND (COALESCE(storage_quota_bytes, ?) <= 0 "
                        + "OR storage_used_bytes + ? <= COALESCE(storage_quota_bytes, ?))",
                bytes, tenantId, defaultStorageQuotaBytes, bytes, defaultStorageQuotaBytes);
        if (updated == 1) return;
        Integer tenants = jdbc.queryForObject("SELECT COUNT(*) FROM vod_tenant WHERE id = ?", Integer.class, tenantId);
        if (tenants != null && tenants > 0) throw new QuotaExceededException();
        // 没有租户行时无从记账，也不拦截；正常凭证都挂在已有租户下。
    }

    public void addUpload(long tenantId, long bytes) {
        String day = LocalDate.now().toString();
        int updated = jdbc.update("UPDATE vod_usage_daily SET storage_bytes = storage_bytes + ?, transcode_jobs = transcode_jobs + 1 "
                + "WHERE tenant_id = ? AND usage_day = ?", bytes, tenantId, day);
        if (updated == 0) {
            jdbc.update("INSERT INTO vod_usage_daily(tenant_id, usage_day, storage_bytes, transcode_jobs) VALUES (?,?,?,1)",
                    tenantId, day, bytes);
        }
    }

    public void addPlay(long tenantId) {
        String day = LocalDate.now().toString();
        int updated = jdbc.update("UPDATE vod_usage_daily SET play_requests = play_requests + 1 WHERE tenant_id = ? AND usage_day = ?",
                tenantId, day);
        if (updated == 0) {
            jdbc.update("INSERT INTO vod_usage_daily(tenant_id, usage_day, play_requests) VALUES (?,?,1)", tenantId, day);
        }
    }

    public Map<String, Object> today(long tenantId) {
        String day = LocalDate.now().toString();
        var rows = jdbc.query("SELECT storage_bytes, play_requests, transcode_jobs FROM vod_usage_daily WHERE tenant_id = ? AND usage_day = ?",
                (rs, n) -> Map.<String, Object>of(
                        "day", day,
                        "storage_bytes", rs.getLong(1),
                        "play_requests", rs.getLong(2),
                        "transcode_jobs", rs.getInt(3)),
                tenantId, day);
        if (rows.isEmpty()) {
            return Map.of("day", day, "storage_bytes", 0, "play_requests", 0, "transcode_jobs", 0);
        }
        return rows.get(0);
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException() {
            super("storage quota exceeded");
        }
    }
}
