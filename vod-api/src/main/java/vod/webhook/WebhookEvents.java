package vod.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在调用方的事务里记一条 Webhook 事件（vod_webhook_event），不发 HTTP。任务字段从 vod_transcode_job 当场取，
 * 和 worker/fencing.py 的 insert_event 是同一条 SQL 形状。事件名见 {@link WebhookService} 常量。
 */
@Component
public class WebhookEvents {
    private final JdbcTemplate jdbc;

    public WebhookEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordForJob(long jobId, String eventType, String reason) {
        jdbc.update("INSERT INTO vod_webhook_event(tenant_id, event_type, job_id, asset_id, claim_version, error_code, reason) "
                + "SELECT tenant_id, ?, id, asset_id, claim_version, error_code, ? FROM vod_transcode_job WHERE id = ?",
                eventType, reason, jobId);
    }
}
