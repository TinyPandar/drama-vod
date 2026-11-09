package vod.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vod.tenant.TenantGuard;
import vod.upload.UploadService;

import java.util.Map;

/** Core 转码任务的状态变更。旧 Demo 的 transcode_job 不在这里，也不提供取消。 */
@Service
public class JobService {
    private final JdbcTemplate jdbc;

    public JobService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 取消：一条条件 UPDATE 把 PENDING/RETRYING/RUNNING 改成 CANCELLED，同时 claim_version+1、清租约，
     * 在跑的 Worker 下次续租或提交时发现围栏丢失并丢弃结果。资产（非 READY）同一事务改为 CANCELLED。
     * 已取消幂等返回；SUCCEEDED/FAILED 抛 409；别的租户或不存在按不可见处理（404）。
     * 匹配到的行状态一定会变，PyMySQL 与 Connector/J 两种 affected rows 语义下都是 1。
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
}
