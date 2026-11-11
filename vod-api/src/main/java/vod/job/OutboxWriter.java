package vod.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vod.upload.UploadService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;

/**
 * 在调用方事务里写一条转码 outbox（消息 v1 + origin=core），提交后走一次快速发布；没确认的由 {@link OutboxPublisher} 兜底。
 * complete、重新转码、重试共用。只给转码队列用，Webhook 事件在 vod_webhook_event。
 */
@Component
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final TranscodePublisher publisher;
    private final ObjectMapper json = new ObjectMapper();

    public OutboxWriter(JdbcTemplate jdbc, TranscodePublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long enqueue(long tenantId, long jobId, long assetId, String traceId) {
        String body = payload(jobId, assetId, traceId);
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO vod_outbox(tenant_id, event_type, payload, status) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, "transcode.requested");
            ps.setString(3, body);
            ps.setString(4, "PENDING");
            return ps;
        }, keys);
        long outboxId = UploadService.generatedId(keys);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (publisher.publish(body)) {
                    jdbc.update("UPDATE vod_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE id = ?", outboxId);
                }
            }
        });
        return outboxId;
    }

    String payload(long jobId, long assetId, String traceId) {
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("schema_version", 1);
            body.put("job_id", jobId);
            body.put("asset_id", assetId);
            body.put("trace_id", traceId == null ? "" : traceId);
            body.put("origin", "core");
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
