package vod.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

@Service
public class UsageService {
    private final JdbcTemplate jdbc;

    public UsageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
}
