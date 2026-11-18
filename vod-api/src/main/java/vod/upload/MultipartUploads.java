package vod.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vod.asset.AssetLifecycle;
import vod.storage.ObjectStorage;
import vod.tenant.TenantGuard;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Phase 6：Multipart 会话的分片签发、断点续传查询、complete 前的存储侧组合、abort。
 *
 * <p>complete 仍是「先让对象在桶里完整存在，再 HEAD 校验，再在同一事务里建任务」：这里只负责把分片组合成源对象，
 * 组合成功后交给 {@link UploadService#complete}（HEAD + UploadPolicy + 配额 + 任务/outbox）。组合失败返回 4xx，
 * 会话保持 OPEN，可以重试；没组合完不建任务。分片信息不进消息 v1。
 */
@Service
public class MultipartUploads {
    private static final Logger log = LoggerFactory.getLogger(MultipartUploads.class);
    private final AssetLifecycle lifecycle;

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final UploadPolicy policy;
    private final UploadService uploads;

    public MultipartUploads(JdbcTemplate jdbc, ObjectStorage storage, UploadPolicy policy, UploadService uploads,
                            AssetLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.jdbc = jdbc;
        this.storage = storage;
        this.policy = policy;
        this.uploads = uploads;
    }

    /** POST /uploads/{id}/parts：为 OPEN、未过期的 multipart 会话签 part URL，有效期不超过会话剩余时间。 */
    public Map<String, Object> presignParts(long tenantId, long sessionId, Object rawPartNumbers) {
        UploadService.Session session = openMultipart(tenantId, sessionId);
        List<Integer> numbers = partNumbers(rawPartNumbers);
        int ttl = Math.min(policy.partUrlTtlSeconds(), UploadService.remainingSeconds(session));
        var parts = new ArrayList<Map<String, Object>>();
        for (int n : numbers) {
            var part = new LinkedHashMap<String, Object>();
            part.put("part_number", n);
            part.put("url", storage.presignUploadPart(UploadService.SOURCE_BUCKET, session.objectKey(), session.uploadId(), n, ttl));
            parts.add(part);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("session_id", session.id());
        out.put("parts", parts);
        out.put("expires_in", ttl);
        out.put("session_expires_at", session.expiresAt().toString());
        return out;
    }

    /** GET /uploads/{id}/parts：存储里已经上传成功的 part 和 etag，客户端据此跳过已传分片。 */
    public Map<String, Object> listParts(long tenantId, long sessionId) {
        UploadService.Session session = openMultipart(tenantId, sessionId);
        List<ObjectStorage.MultipartPart> stored;
        try {
            stored = storage.listParts(UploadService.SOURCE_BUCKET, session.objectKey(), session.uploadId());
        } catch (ObjectStorage.NoSuchUploadException e) {
            throw new UploadService.ConflictException("multipart upload no longer exists");
        }
        var parts = new ArrayList<Map<String, Object>>();
        for (var p : stored) {
            var part = new LinkedHashMap<String, Object>();
            part.put("part_number", p.partNumber());
            part.put("etag", p.etag());
            part.put("size", p.size());
            parts.add(part);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("session_id", session.id());
        out.put("part_size_bytes", policy.partSizeBytes());
        out.put("parts", parts);
        out.put("session_expires_at", session.expiresAt().toString());
        return out;
    }

    /**
     * POST /uploads/{id}/complete 的入口。put 会话、已建过任务、非 OPEN、已过期：原样交给 {@link UploadService#complete}
     * （行为与 Phase 2 相同）。OPEN 的 multipart 会话先用上报的 {part_number, etag} 在存储侧组合：
     * <ul>
     *   <li>etag 对不上、缺 part、顺序不对、非末块太小：400，会话保持 OPEN，可以改正后重试；</li>
     *   <li>upload 已不存在（上一次 complete 已组合成功，但建任务那步失败，比如配额）：桶里有对象就继续走 HEAD 校验，
     *       没有对象就 409；</li>
     * </ul>
     * 组合成功后才进入 HEAD + 校验 + 建任务事务。已过期但仍 OPEN、且存储侧已组合（upload 不存在、桶里有对象）的会话，
     * 也照常进入这个事务（N1，见 {@link #assembledInStorage}）。
     */
    public Map<String, Object> complete(long tenantId, long sessionId, Object rawParts, String traceId) {
        UploadService.Session session = visible(tenantId, sessionId);
        if (session.multipart() && "OPEN".equals(session.status()) && session.expired(Instant.now())
                && !jobExists(session.assetId()) && assembledInStorage(session)) {
            // N1：上次已组合成功、但建任务失败（比如配额 403），之后会话过期。对象已在桶里，照常 HEAD + 校验 + 建任务，
            // 不再 409 卡死。其他过期情况（没组合过）仍走下面原逻辑 409。会话锁内会再复核 OPEN。
            log.info("expired multipart session already assembled, continuing complete session={}", session.id());
            Map<String, Object> result = uploads.complete(tenantId, sessionId, traceId, true);
            uploads.multipartEvent("completed");
            return result;
        }
        if (!session.multipart() || !"OPEN".equals(session.status()) || session.expired(Instant.now())
                || jobExists(session.assetId())) {
            return uploads.complete(tenantId, sessionId, traceId);
        }
        List<ObjectStorage.MultipartPart> parts = completedParts(rawParts);
        try {
            storage.completeMultipart(UploadService.SOURCE_BUCKET, session.objectKey(), session.uploadId(), parts);
        } catch (ObjectStorage.MultipartRejectedException e) {
            uploads.multipartEvent("complete_rejected");
            throw new UploadService.RejectedException(400, e.getMessage());
        } catch (ObjectStorage.NoSuchUploadException e) {
            if (storage.head(UploadService.SOURCE_BUCKET, session.objectKey()).isEmpty()) {
                throw new UploadService.ConflictException("multipart upload no longer exists");
            }
            log.info("multipart upload already assembled, continuing complete session={}", session.id());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            // 网络或存储故障：结果未知，会话仍 OPEN；重试 complete 会走上面 NoSuchUpload + HEAD 的分支。
            log.warn("complete multipart failed session={}: {}", session.id(), e.toString());
            throw new UploadService.StorageUnavailableException("object storage unavailable, retry later");
        }
        Map<String, Object> result = uploads.complete(tenantId, sessionId, traceId);
        uploads.multipartEvent("completed");
        return result;
    }

    /**
     * POST /uploads/{id}/abort：本租户 OPEN 会话可取消（含已过期的），multipart 同时在存储侧 abort。
     * 已 COMPLETED（源文件已校验、可能正在转码）409；已 ABORTED 重复调用 200。
     * 路线 B：资产随之改为 DELETED（原因 UPLOAD_ABORTED），源对象（已经 PUT 或已组合的）排进删除。
     */
    @Transactional
    public Map<String, Object> abort(long tenantId, long sessionId) {
        var rows = jdbc.query("SELECT " + UploadService.SESSION_COLUMNS
                        + " FROM vod_upload_session WHERE id = ? AND tenant_id = ? FOR UPDATE",
                UploadService.SESSION_ROW, sessionId, tenantId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        UploadService.Session session = rows.get(0);
        if ("ABORTED".equals(session.status())) return aborted(session);
        if (!"OPEN".equals(session.status()) || jobExists(session.assetId())) {
            throw new UploadService.ConflictException("upload session is completed, cannot abort");
        }
        if (session.multipart() && session.uploadId() != null) {
            try {
                storage.abortMultipart(UploadService.SOURCE_BUCKET, session.objectKey(), session.uploadId());
            } catch (RuntimeException e) {
                log.warn("abort multipart failed session={}: {}", session.id(), e.toString());
                throw new UploadService.StorageUnavailableException("object storage unavailable, retry later");
            }
        }
        jdbc.update("UPDATE vod_upload_session SET status = 'ABORTED', cleanup_until = NULL, updated_at = ? "
                + "WHERE id = ? AND tenant_id = ? AND status = 'OPEN'", Timestamp.from(Instant.now()), sessionId, tenantId);
        lifecycle.abandonUpload(tenantId, session.assetId(), AssetLifecycle.REASON_UPLOAD_ABORTED);
        uploads.multipartEvent(session.multipart() ? "aborted" : "put_aborted");
        return aborted(session);
    }

    private Map<String, Object> aborted(UploadService.Session session) {
        var out = new LinkedHashMap<String, Object>();
        out.put("session_id", session.id());
        out.put("asset_id", session.assetId());
        out.put("mode", session.mode());
        out.put("session_status", "ABORTED");
        return out;
    }

    /**
     * 存储侧已组合：桶里有目标对象，且 upload id 已不存在（listParts 报 NoSuchUpload）。都是只读调用，在会话锁之外。
     * 存储故障返回 503（结果未知，可以重试），不当成「没组合」。
     */
    private boolean assembledInStorage(UploadService.Session session) {
        try {
            if (session.objectKey() == null
                    || storage.head(UploadService.SOURCE_BUCKET, session.objectKey()).isEmpty()) return false;
            if (session.uploadId() == null) return true;
            try {
                storage.listParts(UploadService.SOURCE_BUCKET, session.objectKey(), session.uploadId());
                return false;  // upload 还在，说明没组合完
            } catch (ObjectStorage.NoSuchUploadException gone) {
                return true;
            }
        } catch (RuntimeException e) {
            log.warn("check assembled multipart failed session={}: {}", session.id(), e.toString());
            throw new UploadService.StorageUnavailableException("object storage unavailable, retry later");
        }
    }

    private UploadService.Session visible(long tenantId, long sessionId) {
        var rows = jdbc.query("SELECT " + UploadService.SESSION_COLUMNS
                        + " FROM vod_upload_session WHERE id = ? AND tenant_id = ?",
                UploadService.SESSION_ROW, sessionId, tenantId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        return rows.get(0);
    }

    /** 跨租户 / 不存在 404；put 会话 409；非 OPEN 或过期 409。 */
    private UploadService.Session openMultipart(long tenantId, long sessionId) {
        UploadService.Session session = visible(tenantId, sessionId);
        if (!session.multipart()) throw new UploadService.ConflictException("not a multipart upload session");
        if (!"OPEN".equals(session.status())) throw new UploadService.ConflictException("upload session is not open");
        if (session.expired(Instant.now())) throw new UploadService.ConflictException("upload session expired");
        if (session.uploadId() == null) throw new UploadService.ConflictException("multipart upload no longer exists");
        return session;
    }

    private boolean jobExists(long assetId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_job WHERE asset_id = ? AND profile = ?",
                Integer.class, assetId, UploadService.PROFILE);
        return n != null && n > 0;
    }

    private List<Integer> partNumbers(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("part_numbers must be a non-empty array");
        }
        if (list.size() > policy.maxPartsPerRequest()) {
            throw new IllegalArgumentException("at most " + policy.maxPartsPerRequest() + " part_numbers per request");
        }
        var out = new TreeSet<Integer>();
        for (Object o : list) out.add(partNumber(o));
        return new ArrayList<>(out);
    }

    private static List<ObjectStorage.MultipartPart> completedParts(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("parts must be a non-empty array of {part_number, etag}");
        }
        if (list.size() > UploadPolicy.MAX_PARTS) throw new IllegalArgumentException("too many parts");
        var out = new ArrayList<ObjectStorage.MultipartPart>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new IllegalArgumentException("parts must be objects");
            Object etag = m.get("etag");
            if (!(etag instanceof String s) || ObjectStorage.unquote(s).isBlank() || s.length() > 256) {
                throw new IllegalArgumentException("etag must be a non-empty string");
            }
            out.add(new ObjectStorage.MultipartPart(partNumber(m.get("part_number")), ObjectStorage.unquote(s), 0));
        }
        // 顺序和重复交给存储判断（S3 要求升序且不重复，否则 InvalidPartOrder，返回 400）。
        return out;
    }

    private static int partNumber(Object o) {
        if (!(o instanceof Integer || o instanceof Long)) throw new IllegalArgumentException("part_number must be an integer");
        long n = ((Number) o).longValue();
        if (n < 1 || n > UploadPolicy.MAX_PARTS) throw new IllegalArgumentException("part_number must be in 1..10000");
        return (int) n;
    }
}
