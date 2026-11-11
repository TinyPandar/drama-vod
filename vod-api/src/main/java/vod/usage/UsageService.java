package vod.usage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vod.quota.QuotaService;

@Service
public class UsageService {
    private final JdbcTemplate jdbc;
    private final long defaultStorageQuotaBytes;
    private final QuotaService quotas;
    private final int maxHistoryDays;

    /** 只给直接 new 的单测用（不经 Spring）：并发/每日配额默认不限制。 */
    public UsageService(JdbcTemplate jdbc, long defaultStorageQuotaBytes) {
        this(jdbc, defaultStorageQuotaBytes, new QuotaService(jdbc, 0, 0), 90);
    }

    /** defaultStorageQuotaBytes 用于 vod_tenant.storage_quota_bytes 为 NULL 的租户；<= 0 表示不限制。 */
    @org.springframework.beans.factory.annotation.Autowired
    public UsageService(JdbcTemplate jdbc,
                        @Value("${vod.quota.default-storage-bytes:0}") long defaultStorageQuotaBytes,
                        QuotaService quotas,
                        @Value("${vod.usage.max-history-days:90}") int maxHistoryDays) {
        this.jdbc = jdbc;
        this.defaultStorageQuotaBytes = defaultStorageQuotaBytes;
        this.quotas = quotas;
        this.maxHistoryDays = Math.max(1, maxHistoryDays);
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

    /** 重新转码、重试各计一次 transcode_jobs（与 complete 一起受每日上限约束）。 */
    public void addTranscode(long tenantId) {
        String day = LocalDate.now().toString();
        int updated = jdbc.update("UPDATE vod_usage_daily SET transcode_jobs = transcode_jobs + 1 WHERE tenant_id = ? AND usage_day = ?",
                tenantId, day);
        if (updated == 0) {
            jdbc.update("INSERT INTO vod_usage_daily(tenant_id, usage_day, transcode_jobs) VALUES (?,?,1)", tenantId, day);
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

    /**
     * GET /api/v1/usage（Phase 5）：保留今天的三列（顶层字段不变），加上配额、已用存储、并发占用；
     * days 给了就返回最近 days 天的历史（含今天，上限 vod.usage.max-history-days，超出截到上限）。
     */
    public Map<String, Object> summary(long tenantId, Integer days) {
        if (days != null && days <= 0) throw new IllegalArgumentException("days must be positive");
        var out = new LinkedHashMap<String, Object>(today(tenantId));
        out.put("tenant_id", tenantId);
        var tenant = jdbc.query("SELECT storage_quota_bytes, storage_used_bytes, max_concurrent_jobs, max_daily_transcodes "
                        + "FROM vod_tenant WHERE id = ?",
                (rs, n) -> new Object[]{rs.getObject(1), rs.getLong(2), rs.getObject(3), rs.getObject(4)}, tenantId);
        Object[] row = tenant.isEmpty() ? new Object[]{null, 0L, null, null} : tenant.get(0);
        long storageQuota = row[0] == null ? defaultStorageQuotaBytes : ((Number) row[0]).longValue();
        int maxConcurrent = row[2] == null ? quotas.defaultMaxConcurrent() : ((Number) row[2]).intValue();
        int maxDaily = row[3] == null ? quotas.defaultMaxDaily() : ((Number) row[3]).intValue();
        var quota = new LinkedHashMap<String, Object>();
        // 0 或负数表示不限制，这里统一写成 null，客户端不必知道这个约定。
        quota.put("storage_bytes", storageQuota > 0 ? storageQuota : null);
        quota.put("max_concurrent_jobs", maxConcurrent > 0 ? maxConcurrent : null);
        quota.put("max_daily_transcodes", maxDaily > 0 ? maxDaily : null);
        out.put("quota", quota);
        out.put("storage_used_bytes", row[1]);
        var concurrency = new LinkedHashMap<String, Object>();
        concurrency.put("active_jobs", quotas.activeJobs(tenantId));
        concurrency.put("running_jobs", quotas.runningJobs(tenantId));
        out.put("concurrency", concurrency);
        out.put("transcodes_today", quotas.transcodesToday(tenantId));
        if (days != null) {
            int n = Math.min(days, maxHistoryDays);
            String from = LocalDate.now().minusDays(n - 1L).toString();
            var byDay = new LinkedHashMap<String, Map<String, Object>>();
            jdbc.query("SELECT usage_day, storage_bytes, play_requests, transcode_jobs FROM vod_usage_daily "
                            + "WHERE tenant_id = ? AND usage_day >= ? ORDER BY usage_day",
                    rs -> {
                        var day = new LinkedHashMap<String, Object>();
                        day.put("day", rs.getString(1));
                        day.put("storage_bytes", rs.getLong(2));
                        day.put("play_requests", rs.getLong(3));
                        day.put("transcode_jobs", rs.getInt(4));
                        byDay.put(rs.getString(1), day);
                    }, tenantId, from);
            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = n - 1; i >= 0; i--) {
                String d = LocalDate.now().minusDays(i).toString();
                Map<String, Object> day = byDay.get(d);
                if (day == null) {
                    day = new LinkedHashMap<>();
                    day.put("day", d);
                    day.put("storage_bytes", 0);
                    day.put("play_requests", 0);
                    day.put("transcode_jobs", 0);
                }
                history.add(day);
            }
            out.put("history", history);
        }
        return out;
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException() {
            super("storage quota exceeded");
        }
    }
}
