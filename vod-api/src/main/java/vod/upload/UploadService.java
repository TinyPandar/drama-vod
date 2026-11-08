package vod.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vod.job.TranscodePublisher;
import vod.storage.ObjectStorage;
import vod.tenant.TenantGuard;
import vod.usage.UsageService;
import vod.webhook.WebhookService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UploadService {
    public static final String PROFILE = "ladder_v1";
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final TranscodePublisher publisher;
    private final UsageService usage;
    private final WebhookService webhooks;
    private final ObjectMapper json = new ObjectMapper();

    public UploadService(JdbcTemplate jdbc, ObjectStorage storage, TranscodePublisher publisher,
                         UsageService usage, WebhookService webhooks) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.publisher = publisher;
        this.usage = usage;
        this.webhooks = webhooks;
    }

    @Transactional
    public Map<String, Object> open(long tenantId, String idempotencyKey, boolean encrypted) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = jdbc.query(
                    "SELECT id, asset_id FROM vod_upload_session WHERE tenant_id = ? AND idempotency_key = ?",
                    (rs, n) -> Map.<String, Object>of("session_id", rs.getLong(1), "asset_id", rs.getLong(2)),
                    tenantId, idempotencyKey);
            if (!existing.isEmpty()) return withPutUrl(tenantId, existing.get(0));
        }
        long assetId = insert(
                "INSERT INTO vod_media_asset(tenant_id, status, source_bucket, source_key, encrypted) VALUES (?,?,?,?,?)",
                tenantId, "UPLOADING", "vod-source", "", encrypted ? 1 : 0);
        String key = "raw/t" + tenantId + "/" + assetId + "/source.mp4";
        jdbc.update("UPDATE vod_media_asset SET source_key = ? WHERE id = ? AND tenant_id = ?", key, assetId, tenantId);
        long sessionId = insert(
                "INSERT INTO vod_upload_session(tenant_id, asset_id, idempotency_key, status) VALUES (?,?,?,?)",
                tenantId, assetId, blankToNull(idempotencyKey), "OPEN");
        return withPutUrl(tenantId, Map.of("session_id", sessionId, "asset_id", assetId));
    }

    @Transactional
    public Map<String, Object> complete(long tenantId, long sessionId, String traceId) {
        var sessions = jdbc.query(
                "SELECT asset_id FROM vod_upload_session WHERE id = ? AND tenant_id = ?",
                (rs, n) -> rs.getLong(1), sessionId, tenantId);
        if (sessions.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        long assetId = sessions.get(0);
        var assets = jdbc.query(
                "SELECT source_bucket, source_key FROM vod_media_asset WHERE id = ? AND tenant_id = ?",
                (rs, n) -> Map.of("bucket", rs.getString(1), "key", rs.getString(2)), assetId, tenantId);
        if (assets.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        String bucket = assets.get(0).get("bucket");
        String key = assets.get(0).get("key");
        if (!storage.exists(bucket, key)) throw new ConflictException("source object not found, upload first");
        long size = storage.size(bucket, key);
        jdbc.update("UPDATE vod_media_asset SET status = 'UPLOADED', source_size = ? WHERE id = ? AND tenant_id = ?",
                size, assetId, tenantId);
        var jobs = jdbc.query(
                "SELECT id, status FROM vod_transcode_job WHERE asset_id = ? AND profile = ?",
                (rs, n) -> Map.<String, Object>of("job_id", rs.getLong(1), "job_status", rs.getString(2)),
                assetId, PROFILE);
        if (!jobs.isEmpty()) return jobResult(assetId, jobs.get(0));
        long jobId;
        try {
            jobId = insert(
                    "INSERT INTO vod_transcode_job(tenant_id, asset_id, profile, status) VALUES (?,?,?,?)",
                    tenantId, assetId, PROFILE, "PENDING");
        } catch (DuplicateKeyException duplicate) {
            // 并发 complete: 另一个事务已经建了同一个 (asset_id, profile) 的任务。
            // FOR UPDATE 读取最新已提交行，避免 REPEATABLE READ 快照看不到它；
            // 异常在这里被吞掉，事务不回滚，本次请求不写 outbox / usage。
            var existing = jdbc.query(
                    "SELECT id, status FROM vod_transcode_job WHERE asset_id = ? AND profile = ? FOR UPDATE",
                    (rs, n) -> Map.<String, Object>of("job_id", rs.getLong(1), "job_status", rs.getString(2)),
                    assetId, PROFILE);
            if (existing.isEmpty()) throw duplicate;
            return jobResult(assetId, existing.get(0));
        }
        String body = payload(jobId, assetId, traceId);
        long outboxId = insert(
                "INSERT INTO vod_outbox(tenant_id, event_type, payload, status) VALUES (?,?,?,?)",
                tenantId, "transcode.requested", body, "PENDING");
        jdbc.update("UPDATE vod_upload_session SET status = 'COMPLETED' WHERE id = ? AND tenant_id = ?", sessionId, tenantId);
        usage.addUpload(tenantId, size);
        publishAfterCommit(outboxId, body, tenantId);
        return Map.of("asset_id", assetId, "job_id", jobId, "job_status", "PENDING");
    }

    public java.util.List<Map<String, Object>> assets(long tenantId) {
        return jdbc.query(
                "SELECT id, status, source_size, encrypted FROM vod_media_asset WHERE tenant_id = ? ORDER BY id DESC",
                (rs, n) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "status", rs.getString("status"),
                        "source_size", rs.getObject("source_size") == null ? 0 : rs.getLong("source_size"),
                        "encrypted", rs.getInt("encrypted") == 1),
                tenantId);
    }

    public java.util.List<Map<String, Object>> jobs(long tenantId) {
        return jdbc.query(
                "SELECT id, asset_id, status, attempts, claim_version FROM vod_transcode_job WHERE tenant_id = ? ORDER BY id DESC",
                (rs, n) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "asset_id", rs.getLong("asset_id"),
                        "status", rs.getString("status"),
                        "attempts", rs.getInt("attempts"),
                        "claim_version", rs.getInt("claim_version")),
                tenantId);
    }

    public Map<String, Object> asset(long tenantId, long assetId) {
        var rows = jdbc.query(
                "SELECT id, tenant_id, status, source_size, encrypted FROM vod_media_asset WHERE id = ?",
                (rs, n) -> {
                    TenantGuard.requireVisible(tenantId, rs.getLong("tenant_id"));
                    return Map.<String, Object>of(
                            "id", rs.getLong("id"),
                            "status", rs.getString("status"),
                            "source_size", rs.getObject("source_size") == null ? 0 : rs.getLong("source_size"),
                            "encrypted", rs.getInt("encrypted") == 1);
                }, assetId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        return rows.get(0);
    }

    private Map<String, Object> withPutUrl(long tenantId, Map<String, Object> ids) {
        long assetId = ((Number) ids.get("asset_id")).longValue();
        String key = jdbc.queryForObject(
                "SELECT source_key FROM vod_media_asset WHERE id = ? AND tenant_id = ?",
                String.class, assetId, tenantId);
        var out = new LinkedHashMap<String, Object>();
        out.put("session_id", ids.get("session_id"));
        out.put("asset_id", assetId);
        out.put("put_url", storage.presignPut("vod-source", key, 900));
        out.put("expires_in", 900);
        return out;
    }

    private static Map<String, Object> jobResult(long assetId, Map<String, Object> job) {
        return Map.of("asset_id", assetId, "job_id", job.get("job_id"), "job_status", job.get("job_status"));
    }

    private String payload(long jobId, long assetId, String traceId) {
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

    private void publishAfterCommit(long outboxId, String body, long tenantId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (publisher.publish(body)) {
                    jdbc.update("UPDATE vod_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE id = ?", outboxId);
                }
                webhooks.dispatch(tenantId, "transcode.requested", body);
            }
        });
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return generatedId(keys);
    }

    public static long generatedId(org.springframework.jdbc.support.GeneratedKeyHolder keys) {
        var row = keys.getKeys();
        if (row != null) {
            for (String name : java.util.List.of("id", "ID", "GENERATED_KEY")) {
                if (row.get(name) instanceof Number number) return number.longValue();
            }
            for (Object value : row.values()) {
                if (value instanceof Number number) return number.longValue();
            }
        }
        throw new IllegalStateException("insert did not return an id");
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
