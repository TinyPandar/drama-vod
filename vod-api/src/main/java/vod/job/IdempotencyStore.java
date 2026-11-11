package vod.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vod.upload.UploadService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 重新转码 / 重试的 Idempotency-Key（vod_idempotency_key，V12）。和业务写入同一事务：
 * 业务失败回滚时 key 一起回滚（下次同 key 可以重新尝试），成功提交后同 key 重放返回同一个任务。
 * fingerprint 记录这次请求的目标（资产+模板、或任务），同一 key 换了目标返回 409。
 * 过了 vod.idempotency.ttl-seconds 的 key 视为不存在（默认值只是开发默认）。
 */
@Component
public class IdempotencyStore {
    private final JdbcTemplate jdbc;
    private final long ttlSeconds;

    public IdempotencyStore(JdbcTemplate jdbc, @Value("${vod.idempotency.ttl-seconds:86400}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.ttlSeconds = Math.max(1, ttlSeconds);
    }

    /** 已处理过的请求：返回 Claim.replayJobId；新请求：返回占位行 id，业务完成后 {@link #complete}。key 为空返回 null。 */
    public record Claim(Long rowId, Long replayJobId) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(long tenantId, String operation, String key, String fingerprint) {
        if (key == null || key.isBlank()) return null;
        if (key.length() > 80) throw new IllegalArgumentException("Idempotency-Key too long (max 80)");
        Claim existing = existing(tenantId, operation, key, fingerprint);
        if (existing != null) return existing;
        try {
            var keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO vod_idempotency_key(tenant_id, operation, idem_key, fingerprint) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, tenantId);
                ps.setString(2, operation);
                ps.setString(3, key);
                ps.setString(4, fingerprint);
                return ps;
            }, keys);
            return new Claim(UploadService.generatedId(keys), null);
        } catch (DuplicateKeyException race) {
            Claim again = existing(tenantId, operation, key, fingerprint);
            if (again == null) throw race;
            return again;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Claim claim, long jobId) {
        if (claim == null || claim.rowId() == null) return;
        jdbc.update("UPDATE vod_idempotency_key SET job_id = ? WHERE id = ?", jobId, claim.rowId());
    }

    private Claim existing(long tenantId, String operation, String key, String fingerprint) {
        List<Object[]> rows = jdbc.query("SELECT id, fingerprint, job_id, created_at FROM vod_idempotency_key "
                        + "WHERE tenant_id = ? AND operation = ? AND idem_key = ? FOR UPDATE",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getObject(3) == null ? null : rs.getLong(3),
                        rs.getTimestamp(4)}, tenantId, operation, key);
        if (rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        Timestamp created = (Timestamp) row[3];
        if (created != null && created.toInstant().plusSeconds(ttlSeconds).isBefore(Instant.now())) {
            jdbc.update("DELETE FROM vod_idempotency_key WHERE id = ?", row[0]);
            return null;
        }
        if (!fingerprint.equals(row[1])) {
            throw new UploadService.ConflictException("Idempotency-Key was used for a different request");
        }
        if (row[2] == null) throw new UploadService.ConflictException("request with this Idempotency-Key is in progress");
        return new Claim(null, (Long) row[2]);
    }
}
