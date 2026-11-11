package vod.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 5 转码配额：同时未结束的任务数、每日新建/重转/重试次数。超额统一 429（契约写死），
 * body 的 code 是 quota_exceeded、quota 指明哪一项；每日上限带 Retry-After（到本地次日零点的秒数）。
 *
 * <p>调用方事务里先 {@link #lockTenant} 锁住租户行（SELECT … FOR UPDATE），同一租户建任务的事务因此串行，
 * 计数与插入之间不会被并发请求挤过上限。加锁顺序：租户 → 任务 → 资产，和取消（任务 → 资产 → 前缀）、
 * Worker（任务 → 资产 → 前缀）不成环。存储配额仍是 {@link vod.usage.UsageService#reserveStorage}（403，Phase 2 契约）。
 */
@Service
public class QuotaService {
    private final JdbcTemplate jdbc;
    private final int defaultMaxConcurrent;
    private final int defaultMaxDaily;

    /** 默认 0 = 不限制；只是开发默认，不是容量承诺。 */
    public QuotaService(JdbcTemplate jdbc,
                        @Value("${vod.quota.default-max-concurrent-jobs:0}") int defaultMaxConcurrent,
                        @Value("${vod.quota.default-max-daily-transcodes:0}") int defaultMaxDaily) {
        this.jdbc = jdbc;
        this.defaultMaxConcurrent = defaultMaxConcurrent;
        this.defaultMaxDaily = defaultMaxDaily;
    }

    public record Limits(Long storageQuotaBytes, long storageUsedBytes, int maxConcurrentJobs, int maxDailyTranscodes) {}

    /** 锁租户行并返回生效的上限；租户行不存在（只有旧测试数据会这样）返回 null，调用方不做配额判断。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Limits lockTenant(long tenantId) {
        List<Limits> rows = jdbc.query(
                "SELECT storage_quota_bytes, storage_used_bytes, max_concurrent_jobs, max_daily_transcodes "
                        + "FROM vod_tenant WHERE id = ? FOR UPDATE",
                (rs, n) -> new Limits(
                        rs.getObject(1) == null ? null : rs.getLong(1),
                        rs.getLong(2),
                        effective(rs.getObject(3), defaultMaxConcurrent),
                        effective(rs.getObject(4), defaultMaxDaily)),
                tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁租户行并检查（complete 用）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkNewTranscode(long tenantId) {
        check(tenantId, lockTenant(tenantId));
    }

    /** 已经 {@link #lockTenant} 过的事务里，在建任务（或把终态任务改回 PENDING）之前调用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void check(long tenantId) {
        check(tenantId, lockTenant(tenantId));
    }

    private void check(long tenantId, Limits limits) {
        if (limits == null) return;
        if (limits.maxConcurrentJobs() > 0 && activeJobs(tenantId) >= limits.maxConcurrentJobs()) {
            throw new TranscodeQuotaException("concurrent_jobs", 0);
        }
        if (limits.maxDailyTranscodes() > 0 && transcodesToday(tenantId) >= limits.maxDailyTranscodes()) {
            LocalDateTime now = LocalDateTime.now();
            long untilMidnight = Duration.between(now, LocalDate.now().plusDays(1).atStartOfDay()).toSeconds();
            throw new TranscodeQuotaException("daily_transcodes", Math.max(1, untilMidnight));
        }
    }

    public int activeJobs(long tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job WHERE tenant_id = ? "
                + "AND status IN ('PENDING','RETRYING','RUNNING')", Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    public int runningJobs(long tenantId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job WHERE tenant_id = ? AND status = 'RUNNING'",
                Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    public int transcodesToday(long tenantId) {
        List<Integer> rows = jdbc.queryForList("SELECT transcode_jobs FROM vod_usage_daily WHERE tenant_id = ? AND usage_day = ?",
                Integer.class, tenantId, LocalDate.now().toString());
        return rows.isEmpty() || rows.get(0) == null ? 0 : rows.get(0);
    }

    public int defaultMaxConcurrent() {
        return defaultMaxConcurrent;
    }

    public int defaultMaxDaily() {
        return defaultMaxDaily;
    }

    private static int effective(Object column, int fallback) {
        return column == null ? fallback : ((Number) column).intValue();
    }

    /** 429 + code=quota_exceeded；retryAfterSeconds 为 0 时不写 Retry-After（并发上限要等任务结束，时间不可预知）。 */
    public static class TranscodeQuotaException extends RuntimeException {
        private final String quota;
        private final long retryAfterSeconds;

        public TranscodeQuotaException(String quota, long retryAfterSeconds) {
            super(quota + " quota exceeded");
            this.quota = quota;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String quota() {
            return quota;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
