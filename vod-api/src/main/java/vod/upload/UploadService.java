package vod.upload;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vod.job.OutboxWriter;
import vod.media.PosterUrls;
import vod.quota.QuotaService;
import vod.storage.ObjectHead;
import vod.storage.ObjectStorage;
import vod.tenant.TenantGuard;
import vod.usage.UsageService;
import vod.webhook.WebhookEvents;
import vod.webhook.WebhookService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UploadService {
    public static final String PROFILE = "ladder_v1";
    public static final String SOURCE_BUCKET = "vod-source";
    public static final String ROLE_SOURCE = "SOURCE";
    public static final String MODE_PUT = "put";
    public static final String MODE_MULTIPART = "multipart";
    static final String SESSION_COLUMNS = "id, asset_id, status, object_key, expires_at, upload_mode, multipart_upload_id";
    static final RowMapper<Session> SESSION_ROW = (rs, n) -> new Session(
            rs.getLong("id"), rs.getLong("asset_id"), rs.getString("status"), rs.getString("object_key"),
            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
            rs.getString("upload_mode") == null ? MODE_PUT : rs.getString("upload_mode"),
            rs.getString("multipart_upload_id"));
    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final OutboxWriter outbox;
    private final UsageService usage;
    private final QuotaService quotas;
    private final WebhookEvents events;
    private final UploadPolicy policy;
    private final PosterUrls posters;
    private final MeterRegistry registry;

    public UploadService(JdbcTemplate jdbc, ObjectStorage storage, OutboxWriter outbox,
                         UsageService usage, QuotaService quotas, WebhookEvents events, UploadPolicy policy,
                         PosterUrls posters, MeterRegistry registry) {
        this.posters = posters;
        this.registry = registry;
        this.jdbc = jdbc;
        this.storage = storage;
        this.outbox = outbox;
        this.usage = usage;
        this.quotas = quotas;
        this.events = events;
        this.policy = policy;
    }

    @Transactional
    public Map<String, Object> open(long tenantId, String idempotencyKey, boolean encrypted, String filename) {
        return open(tenantId, idempotencyKey, encrypted, filename, null, null);
    }

    /**
     * mode 缺省或 put：与 Phase 2 完全相同（返回 put_url）。mode=multipart（Phase 6）：在源桶创建 S3 multipart，
     * upload id 记进会话，返回 part_size_bytes 等，不返回 put_url；分片 URL 走 POST /uploads/{id}/parts。
     * contentType 只对 multipart 有意义（写进最终对象）；complete 仍以 HEAD 为准。
     */
    @Transactional
    public Map<String, Object> open(long tenantId, String idempotencyKey, boolean encrypted, String filename,
                                    String mode, String contentType) {
        String uploadMode = mode(mode);
        String ext = policy.extensionFor(filename);
        String multipartType = MODE_MULTIPART.equals(uploadMode) ? policy.multipartContentType(ext, contentType) : null;
        String idem = blankToNull(idempotencyKey);
        if (idem != null) {
            // 重放按原会话的方式返回（multipart 不会再 create 一次 S3 上传），与这次请求里的 mode 无关。
            var existing = sessionByIdempotencyKey(tenantId, idem, false);
            if (!existing.isEmpty()) return replay(existing.get(0));
        }
        int ttl = MODE_MULTIPART.equals(uploadMode) ? policy.multipartSessionTtlSeconds() : policy.sessionTtlSeconds();
        Instant expiresAt = Instant.now().plusSeconds(ttl);
        long sessionId;
        try {
            // 先占 (tenant_id, idempotency_key) 唯一键，再建资产：并发的同 key 请求在这里撞键，不会各建一份。
            sessionId = insert(
                    "INSERT INTO vod_upload_session(tenant_id, asset_id, idempotency_key, status, expires_at, upload_mode) "
                            + "VALUES (?,?,?,?,?,?)",
                    tenantId, 0L, idem, "OPEN", Timestamp.from(expiresAt), uploadMode);
        } catch (DuplicateKeyException duplicate) {
            // FOR UPDATE 读最新已提交行，REPEATABLE READ 快照看不到对方刚提交的会话。
            var existing = sessionByIdempotencyKey(tenantId, idem, true);
            if (existing.isEmpty()) throw duplicate;
            return replay(existing.get(0));
        }
        long assetId = insert(
                "INSERT INTO vod_media_asset(tenant_id, status, source_bucket, source_key, encrypted) VALUES (?,?,?,?,?)",
                tenantId, "UPLOADING", SOURCE_BUCKET, "", encrypted ? 1 : 0);
        String key = "raw/t" + tenantId + "/" + assetId + "/source." + ext;
        long objectId = insert(
                "INSERT INTO vod_storage_object(tenant_id, bucket, object_key, role) VALUES (?,?,?,?)",
                tenantId, SOURCE_BUCKET, key, ROLE_SOURCE);
        // source_bucket / source_key 与 storage_object 同一事务双写：Python Worker 仍直接读这两列。
        jdbc.update("UPDATE vod_media_asset SET source_key = ?, source_object_id = ? WHERE id = ? AND tenant_id = ?",
                key, objectId, assetId, tenantId);
        jdbc.update("UPDATE vod_upload_session SET asset_id = ?, object_key = ? WHERE id = ? AND tenant_id = ?",
                assetId, key, sessionId, tenantId);
        if (MODE_PUT.equals(uploadMode)) {
            return putResponse(new Session(sessionId, assetId, "OPEN", key, expiresAt, MODE_PUT, null));
        }
        // 放在最后一步：前面的插入失败就不会在存储侧留下 upload；本事务之后若回滚，提交后回调里 abort 掉。
        String uploadId = storage.createMultipart(SOURCE_BUCKET, key, multipartType);
        abortIfRolledBack(key, uploadId);
        jdbc.update("UPDATE vod_upload_session SET multipart_upload_id = ? WHERE id = ? AND tenant_id = ?",
                uploadId, sessionId, tenantId);
        multipartEvent("created");
        return multipartResponse(new Session(sessionId, assetId, "OPEN", key, expiresAt, MODE_MULTIPART, uploadId));
    }

    /** GET /api/v1/uploads/config：控制台据此选 put / multipart 并切片，不在前端写死。 */
    public Map<String, Object> config() {
        var out = new LinkedHashMap<String, Object>();
        out.put("modes", java.util.List.of(MODE_PUT, MODE_MULTIPART));
        out.put("part_size_bytes", policy.partSizeBytes());
        out.put("multipart_threshold_bytes", policy.multipartThresholdBytes());
        out.put("max_parts", UploadPolicy.MAX_PARTS);
        out.put("max_parts_per_request", policy.maxPartsPerRequest());
        out.put("max_object_bytes", policy.maxObjectBytes() > 0 ? policy.maxObjectBytes() : null);
        out.put("session_ttl_seconds", policy.sessionTtlSeconds());
        out.put("multipart_session_ttl_seconds", policy.multipartSessionTtlSeconds());
        return out;
    }

    void multipartEvent(String event) {
        registry.counter("vod.upload.multipart", "event", event).increment();
    }

    private static String mode(String mode) {
        if (mode == null || mode.isBlank() || MODE_PUT.equals(mode)) return MODE_PUT;
        if (MODE_MULTIPART.equals(mode)) return MODE_MULTIPART;
        throw new IllegalArgumentException("mode must be put or multipart");
    }

    /** 事务回滚（例如提交失败）时把刚建的存储侧 upload 取消掉，避免留下没有会话指向的未完成分片。 */
    private void abortIfRolledBack(String key, String uploadId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                try {
                    storage.abortMultipart(SOURCE_BUCKET, key, uploadId);
                } catch (RuntimeException e) {
                    log.warn("abort multipart after rollback failed: {}", e.toString());
                }
            }
        });
    }

    /**
     * 先 HEAD 再校验再在同一事务建任务。multipart 会话由 {@link MultipartUploads#complete} 先在存储侧组合好再调这里。
     * Phase 6：会话行 FOR UPDATE，与 abort / 过期清理互斥；加锁顺序 会话 → 租户 → 对象 → 资产 → 任务。
     */
    @Transactional
    public Map<String, Object> complete(long tenantId, long sessionId, String traceId) {
        return complete(tenantId, sessionId, traceId, false);
    }

    /**
     * allowExpiredAssembled 只由 {@link MultipartUploads#complete} 传 true：会话已过期、但存储侧已组合好（N1），
     * 仍要求 OPEN，HEAD / 校验 / 配额照常；其他调用方行为不变。
     */
    @Transactional
    public Map<String, Object> complete(long tenantId, long sessionId, String traceId, boolean allowExpiredAssembled) {
        var sessions = jdbc.query(
                "SELECT " + SESSION_COLUMNS + " FROM vod_upload_session WHERE id = ? AND tenant_id = ? FOR UPDATE",
                SESSION_ROW, sessionId, tenantId);
        if (sessions.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        Session session = sessions.get(0);
        long assetId = session.assetId();
        var jobs = jdbc.query(
                "SELECT id, status FROM vod_transcode_job WHERE asset_id = ? AND profile = ?",
                (rs, n) -> Map.<String, Object>of("job_id", rs.getLong(1), "job_status", rs.getString(2)),
                assetId, PROFILE);
        // 已经建过任务：重复 complete 直接返回原任务，不再 HEAD，也不把资产状态改回 UPLOADED。
        if (!jobs.isEmpty()) return jobResult(assetId, jobs.get(0));
        if (!"OPEN".equals(session.status())) throw new ConflictException("upload session is not open");
        if (session.expired(Instant.now()) && !(allowExpiredAssembled && session.multipart())) {
            throw new ConflictException("upload session expired");
        }
        var sources = jdbc.query(
                "SELECT o.id, o.bucket, o.object_key FROM vod_media_asset a "
                        + "JOIN vod_storage_object o ON o.id = a.source_object_id AND o.tenant_id = a.tenant_id "
                        + "WHERE a.id = ? AND a.tenant_id = ?",
                (rs, n) -> new SourceRef(rs.getLong(1), rs.getString(2), rs.getString(3)), assetId, tenantId);
        if (sources.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        SourceRef source = sources.get(0);
        ObjectHead head = storage.head(source.bucket(), source.key())
                .orElseThrow(() -> new ConflictException("source object not found, upload first"));
        policy.check(source.key(), head);
        // Phase 5：锁租户行后检查并发与每日转码配额（429），同一租户建任务的事务从这里开始串行。
        // 放在 HEAD 之后，不在锁里等对象存储；加锁顺序会话 → 租户 → 对象 → 资产 → 任务，见 QuotaService。
        quotas.checkNewTranscode(tenantId);
        jdbc.update("UPDATE vod_storage_object SET size = ?, etag = ?, content_type = ?, checksum = ? WHERE id = ? AND tenant_id = ?",
                head.size(), head.etag(), head.contentType(), head.checksum(), source.id(), tenantId);
        jdbc.update("UPDATE vod_media_asset SET status = 'UPLOADED', source_size = ? WHERE id = ? AND tenant_id = ?",
                head.size(), assetId, tenantId);
        long jobId;
        try {
            jobId = insert(
                    "INSERT INTO vod_transcode_job(tenant_id, asset_id, profile, status) VALUES (?,?,?,?)",
                    tenantId, assetId, PROFILE, "PENDING");
        } catch (DuplicateKeyException duplicate) {
            // 并发 complete: 另一个事务已经建了同一个 (asset_id, profile) 的任务。
            // FOR UPDATE 读取最新已提交行，避免 REPEATABLE READ 快照看不到它；
            // 异常在这里被吞掉，事务不回滚，本次请求不写 outbox / usage / 配额。
            var existing = jdbc.query(
                    "SELECT id, status FROM vod_transcode_job WHERE asset_id = ? AND profile = ? FOR UPDATE",
                    (rs, n) -> Map.<String, Object>of("job_id", rs.getLong(1), "job_status", rs.getString(2)),
                    assetId, PROFILE);
            if (existing.isEmpty()) throw duplicate;
            return jobResult(assetId, existing.get(0));
        }
        // 配额放在任务插入成功之后：并发重复 complete 只有赢家占用；超限抛异常，整个事务（含任务）回滚。
        usage.reserveStorage(tenantId, head.size());
        outbox.enqueue(tenantId, jobId, assetId, traceId);
        jdbc.update("UPDATE vod_upload_session SET status = 'COMPLETED', checksum = ?, updated_at = ? WHERE id = ? AND tenant_id = ?",
                head.checksum(), Timestamp.from(Instant.now()), sessionId, tenantId);
        usage.addUpload(tenantId, head.size());
        // Phase 5：不再在 afterCommit 里同步发 HTTP；事件随本事务提交，由 WebhookDispatcher 异步投递。
        events.recordForJob(jobId, WebhookService.REQUESTED, "upload");
        return Map.of("asset_id", assetId, "job_id", jobId, "job_status", "PENDING");
    }

    /** 按 id 倒序的游标分页：cursor 是上一页最后一个 id，next_cursor 为 null 表示没有下一页。 */
    public Map<String, Object> assets(long tenantId, int limit, Long cursor) {
        String sql = "SELECT id, status, source_size, encrypted, hls_prefix, poster_key FROM vod_media_asset WHERE tenant_id = ?"
                + (cursor == null ? "" : " AND id < ?") + " ORDER BY id DESC LIMIT ?";
        Object[] args = cursor == null ? new Object[]{tenantId, limit + 1} : new Object[]{tenantId, cursor, limit + 1};
        long exp = posters.defaultExpiry();
        var rows = jdbc.query(sql,
                (rs, n) -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", rs.getLong("id"));
                    row.put("status", rs.getString("status"));
                    row.put("source_size", rs.getObject("source_size") == null ? 0 : rs.getLong("source_size"));
                    row.put("encrypted", rs.getInt("encrypted") == 1);
                    // Phase 6：封面签名地址，没有封面为 null。
                    row.put("poster_url", posters.urlOrNull(rs.getLong("id"), rs.getString("hls_prefix"),
                            rs.getString("poster_key"), exp));
                    return (Map<String, Object>) row;
                },
                args);
        boolean more = rows.size() > limit;
        var page = more ? rows.subList(0, limit) : rows;
        var out = new LinkedHashMap<String, Object>();
        out.put("assets", page);
        out.put("next_cursor", more ? page.get(page.size() - 1).get("id") : null);
        return out;
    }

    public java.util.List<Map<String, Object>> jobs(long tenantId) {
        return jdbc.query(
                "SELECT id, asset_id, profile, status, attempts, claim_version FROM vod_transcode_job WHERE tenant_id = ? ORDER BY id DESC",
                (rs, n) -> Map.<String, Object>of(
                        "id", rs.getLong("id"),
                        "asset_id", rs.getLong("asset_id"),
                        "profile", rs.getString("profile"),
                        "status", rs.getString("status"),
                        "attempts", rs.getInt("attempts"),
                        "claim_version", rs.getInt("claim_version")),
                tenantId);
    }

    public Map<String, Object> job(long tenantId, long jobId) {
        var rows = jdbc.query(
                "SELECT id, tenant_id, asset_id, profile, status, attempts, max_attempts, claim_version, "
                        + "locked_until, last_error, error_code, next_attempt_at, updated_at, template_id FROM vod_transcode_job WHERE id = ?",
                (rs, n) -> {
                    TenantGuard.requireVisible(tenantId, rs.getLong("tenant_id"));
                    var out = new LinkedHashMap<String, Object>();
                    out.put("id", rs.getLong("id"));
                    out.put("asset_id", rs.getLong("asset_id"));
                    out.put("profile", rs.getString("profile"));
                    out.put("status", rs.getString("status"));
                    out.put("attempts", rs.getInt("attempts"));
                    out.put("max_attempts", rs.getInt("max_attempts"));
                    out.put("claim_version", rs.getInt("claim_version"));
                    out.put("locked_until", iso(rs.getTimestamp("locked_until")));
                    out.put("last_error", rs.getString("last_error"));
                    out.put("error_code", rs.getString("error_code"));
                    out.put("next_attempt_at", iso(rs.getTimestamp("next_attempt_at")));
                    out.put("updated_at", iso(rs.getTimestamp("updated_at")));
                    out.put("template_id", rs.getObject("template_id") == null ? null : rs.getLong("template_id"));
                    return out;
                }, jobId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        return rows.get(0);
    }

    public Map<String, Object> asset(long tenantId, long assetId) {
        var rows = jdbc.query(
                "SELECT a.id, a.tenant_id, a.status, a.source_size, a.encrypted, a.hls_prefix, a.duration_ms, a.width, a.height, a.poster_key, "
                        + "o.id AS object_id, o.bucket AS object_bucket, o.object_key AS object_key, o.size AS object_size, "
                        + "o.etag AS object_etag, o.content_type AS object_content_type, o.checksum AS object_checksum "
                        + "FROM vod_media_asset a "
                        + "LEFT JOIN vod_storage_object o ON o.id = a.source_object_id AND o.tenant_id = a.tenant_id "
                        + "WHERE a.id = ?",
                (rs, n) -> {
                    TenantGuard.requireVisible(tenantId, rs.getLong("tenant_id"));
                    var out = new LinkedHashMap<String, Object>();
                    out.put("id", rs.getLong("id"));
                    out.put("status", rs.getString("status"));
                    out.put("source_size", rs.getObject("source_size") == null ? 0 : rs.getLong("source_size"));
                    out.put("encrypted", rs.getInt("encrypted") == 1);
                    out.put("hls_prefix", rs.getString("hls_prefix"));
                    out.put("duration_ms", rs.getObject("duration_ms") == null ? null : rs.getInt("duration_ms"));
                    out.put("width", rs.getObject("width") == null ? null : rs.getInt("width"));
                    out.put("height", rs.getObject("height") == null ? null : rs.getInt("height"));
                    out.put("poster_url", posters.urlOrNull(rs.getLong("id"), rs.getString("hls_prefix"),
                            rs.getString("poster_key"), posters.defaultExpiry()));
                    Map<String, Object> source = null;
                    if (rs.getObject("object_id") != null) {
                        source = new LinkedHashMap<>();
                        source.put("object_id", rs.getLong("object_id"));
                        source.put("bucket", rs.getString("object_bucket"));
                        source.put("object_key", rs.getString("object_key"));
                        source.put("size", rs.getObject("object_size") == null ? null : rs.getLong("object_size"));
                        source.put("etag", rs.getString("object_etag"));
                        source.put("content_type", rs.getString("object_content_type"));
                        source.put("checksum", rs.getString("object_checksum"));
                    }
                    out.put("source", source);
                    return out;
                }, assetId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        return rows.get(0);
    }

    private java.util.List<Session> sessionByIdempotencyKey(long tenantId, String idem, boolean forUpdate) {
        return jdbc.query(
                "SELECT " + SESSION_COLUMNS + " FROM vod_upload_session "
                        + "WHERE tenant_id = ? AND idempotency_key = ?" + (forUpdate ? " FOR UPDATE" : ""),
                SESSION_ROW, tenantId, idem);
    }

    /**
     * 同一 Idempotency-Key 重放：OPEN 会话返回原会话和新的预签名 PUT，有效期不超过会话剩余时间，过期则 409。
     * 非 OPEN（例如已 COMPLETED）不再签发 put_url：转码期间再 PUT 会覆盖已经 HEAD 校验过的源文件，
     * 只返回会话、资产与已有任务信息。
     */
    private Map<String, Object> replay(Session session) {
        if (!"OPEN".equals(session.status())) return sessionInfo(session);
        if (session.expired(Instant.now())) throw new ConflictException("upload session expired");
        return session.multipart() ? multipartResponse(session) : putResponse(session);
    }

    private Map<String, Object> sessionInfo(Session session) {
        var jobs = jdbc.query("SELECT id, status FROM vod_transcode_job WHERE asset_id = ? AND profile = ?",
                (rs, n) -> Map.<String, Object>of("job_id", rs.getLong(1), "job_status", rs.getString(2)),
                session.assetId(), PROFILE);
        var out = new LinkedHashMap<String, Object>();
        out.put("session_id", session.id());
        out.put("asset_id", session.assetId());
        out.put("session_status", session.status());
        if (session.multipart()) out.put("mode", MODE_MULTIPART);
        out.put("expires_at", session.expiresAt() == null ? null : session.expiresAt().toString());
        out.put("job_id", jobs.isEmpty() ? null : jobs.get(0).get("job_id"));
        out.put("job_status", jobs.isEmpty() ? null : jobs.get(0).get("job_status"));
        return out;
    }

    private Map<String, Object> putResponse(Session session) {
        long remainingMillis = Duration.between(Instant.now(), session.expiresAt()).toMillis();
        int ttl = (int) Math.max(1, Math.min(policy.sessionTtlSeconds(), (remainingMillis + 999) / 1000));
        var out = new LinkedHashMap<String, Object>();
        out.put("session_id", session.id());
        out.put("asset_id", session.assetId());
        out.put("put_url", storage.presignPut(SOURCE_BUCKET, session.objectKey(), ttl));
        out.put("expires_in", ttl);
        out.put("expires_at", session.expiresAt().toString());
        return out;
    }

    /** Phase 6 multipart 会话：不签 put_url；分片 URL 走 POST /uploads/{id}/parts。 */
    private Map<String, Object> multipartResponse(Session session) {
        var out = new LinkedHashMap<String, Object>();
        out.put("session_id", session.id());
        out.put("asset_id", session.assetId());
        out.put("mode", MODE_MULTIPART);
        out.put("part_size_bytes", policy.partSizeBytes());
        out.put("max_parts", UploadPolicy.MAX_PARTS);
        out.put("expires_in", remainingSeconds(session));
        out.put("expires_at", session.expiresAt().toString());
        return out;
    }

    static int remainingSeconds(Session session) {
        long remainingMillis = Duration.between(Instant.now(), session.expiresAt()).toMillis();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (remainingMillis + 999) / 1000));
    }

    private static String iso(Timestamp ts) {
        return ts == null ? null : ts.toInstant().toString();
    }

    private static Map<String, Object> jobResult(long assetId, Map<String, Object> job) {
        return Map.of("asset_id", assetId, "job_id", job.get("job_id"), "job_status", job.get("job_status"));
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

    /** 对象存储暂时不可用（Phase 6 abort / complete multipart）：503，会话状态不变，可以重试。 */
    public static class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(String message) {
            super(message);
        }
    }

    /** 源对象没通过 HEAD 校验（大小、扩展名、MIME）。status 是返回给客户端的 HTTP 状态码。 */
    public static class RejectedException extends RuntimeException {
        private final int status;

        public RejectedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    record Session(long id, long assetId, String status, String objectKey, Instant expiresAt, String mode,
                   String uploadId) {
        boolean expired(Instant now) {
            return expiresAt == null || !expiresAt.isAfter(now);
        }

        boolean multipart() {
            return MODE_MULTIPART.equals(mode);
        }
    }

    private record SourceRef(long id, String bucket, String key) {
    }
}
