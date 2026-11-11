package vod.job;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vod.tenant.TenantGuard;
import vod.upload.UploadService;
import vod.webhook.WebhookEvents;
import vod.webhook.WebhookService;

import java.util.Map;

/** Core 转码任务的状态变更。旧 Demo 的 transcode_job 不在这里，也不提供取消。 */
@Service
public class JobService {
    private final JdbcTemplate jdbc;
    private final WebhookEvents events;

    /** 只给直接 new 的单测用（不经 Spring 代理，事件 SQL 直接执行）。 */
    public JobService(JdbcTemplate jdbc) {
        this(jdbc, new WebhookEvents(jdbc));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JobService(JdbcTemplate jdbc, WebhookEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /**
     * 取消：一条条件 UPDATE 把 PENDING/RETRYING/RUNNING 改成 CANCELLED，同时 claim_version+1、清租约，
     * 在跑的 Worker 下次续租或提交时发现围栏丢失并丢弃结果。资产（非 READY）同一事务改为 CANCELLED。
     * 已取消幂等返回；SUCCEEDED/FAILED 抛 409；别的租户或不存在按不可见处理（404）。
     * 匹配到的行状态一定会变，PyMySQL 与 Connector/J 两种 affected rows 语义下都是 1。
     *
     * <p>Phase 4：同一事务里把该任务还在 WRITING 的输出前缀标成 GARBAGE（vod_output_prefix），Worker 的清理线程过了宽限期再删。
     * 加锁顺序是任务 → 资产 → 前缀，和 Worker 的 TRANSCODING / 成功 / 失败事务一致。
     * 死锁或锁等待超时由调用方在事务外重试一次（{@link #cancelWithRetry}）。
     *
     * <p>Phase 5：同一事务里记一条 transcode.cancelled 事件（reason=cancelled），由 WebhookDispatcher 异步投递；
     * 已取消的重复调用不再记事件。
     */
    @Transactional
    public void cancel(long tenantId, long jobId) {
        int updated = jdbc.update(
                "UPDATE vod_transcode_job SET status = 'CANCELLED', claim_version = claim_version + 1, "
                        + "locked_until = NULL, next_attempt_at = NULL, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND tenant_id = ? AND status IN ('PENDING','RETRYING','RUNNING')",
                jobId, tenantId);
        if (updated == 1) {
            Long assetId = jdbc.queryForObject("SELECT asset_id FROM vod_transcode_job WHERE id = ?", Long.class, jobId);
            jdbc.update("UPDATE vod_media_asset SET status = 'CANCELLED' WHERE id = ? AND tenant_id = ? AND status <> 'READY'",
                    assetId, tenantId);
            jdbc.update("UPDATE vod_output_prefix SET status = 'GARBAGE', updated_at = CURRENT_TIMESTAMP "
                    + "WHERE job_id = ? AND status = 'WRITING'", jobId);
            events.recordForJob(jobId, WebhookService.CANCELLED, "cancelled");
            return;
        }
        var rows = jdbc.query("SELECT tenant_id, status FROM vod_transcode_job WHERE id = ?",
                (rs, n) -> Map.entry(rs.getLong(1), rs.getString(2)), jobId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        TenantGuard.requireVisible(tenantId, rows.get(0).getKey());
        String status = rows.get(0).getValue();
        if ("CANCELLED".equals(status)) return;
        throw new UploadService.ConflictException("job is " + status + ", cannot cancel");
    }

    /**
     * 在 @Transactional 外层调用：死锁被选为牺牲者或锁等待超时（PessimisticLockingFailureException 及其子类）时整体重试一次，
     * 仍失败抛 {@link BusyException}（503）。传入的 cancel 必须经过 Spring 代理，才能每次开新事务。
     */
    public static void cancelWithRetry(Runnable cancel) {
        try {
            cancel.run();
        } catch (PessimisticLockingFailureException first) {
            try {
                cancel.run();
            } catch (PessimisticLockingFailureException second) {
                throw new BusyException(second);
            }
        }
    }

    public static class BusyException extends RuntimeException {
        public BusyException(Throwable cause) {
            super("lock conflict, retry later", cause);
        }
    }
}
