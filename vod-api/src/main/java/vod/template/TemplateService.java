package vod.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vod.admin.TenantAdminService;
import vod.tenant.TenantGuard;
import vod.upload.UploadService;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 转码模板（vod_transcode_template，V12）。tenant_id = 0 是系统模板；模板 name 就是任务的 profile，
 * 所以 (asset_id, profile) 唯一键继续成立：同一资产同一模板永远只有一行任务，重新转码复用它。
 * Worker 按 job_id → template_id 读 ladder_json / audio_kbps，消息 v1 不带梯队。
 *
 * <p>系统模板默认不允许改（vod.template.allow-system-edit=false），避免演示时悄悄改掉老资产的行为。
 * 租户模板名不能和系统模板重名。编码器、封装目前只支持 h264 + HLS TS。
 */
@Service
public class TemplateService {
    public static final long SYSTEM_TENANT = 0;
    private static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern RUNG_NAME = Pattern.compile("[0-9]{2,4}p");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RowMapper<Template> ROW = (rs, n) -> new Template(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("name"), rs.getString("description"),
            rs.getString("video_codec"), rs.getString("container"), rs.getString("ladder_json"), rs.getInt("audio_kbps"),
            rs.getString("status"), rs.getTimestamp("updated_at"));
    private static final String SELECT = "SELECT id, tenant_id, name, description, video_codec, container, ladder_json, "
            + "audio_kbps, status, updated_at FROM vod_transcode_template";

    private final JdbcTemplate jdbc;
    private final String defaultName;
    private final boolean allowSystemEdit;

    public TemplateService(JdbcTemplate jdbc,
                           @Value("${vod.template.default-name:ladder_v1}") String defaultName,
                           @Value("${vod.template.allow-system-edit:false}") boolean allowSystemEdit) {
        this.jdbc = jdbc;
        this.defaultName = defaultName;
        this.allowSystemEdit = allowSystemEdit;
    }

    public record Template(long id, long tenantId, String name, String description, String videoCodec, String container,
                           String ladderJson, int audioKbps, String status, Timestamp updatedAt) {
        public Map<String, Object> view() {
            var out = new LinkedHashMap<String, Object>();
            out.put("id", id);
            out.put("name", name);
            out.put("system", tenantId == SYSTEM_TENANT);
            if (tenantId != SYSTEM_TENANT) out.put("tenant_id", tenantId);
            out.put("description", description);
            out.put("video_codec", videoCodec);
            out.put("container", container);
            try {
                out.put("ladder", JSON.readTree(ladderJson));
            } catch (JsonProcessingException e) {
                out.put("ladder", null);
            }
            out.put("audio_kbps", audioKbps);
            out.put("status", status);
            out.put("updated_at", updatedAt == null ? null : updatedAt.toInstant().toString());
            return out;
        }
    }

    /** 租户可见：系统模板 + 自己的模板（只列 ACTIVE）。 */
    public List<Map<String, Object>> visible(long tenantId) {
        return jdbc.query(SELECT + " WHERE tenant_id IN (?, ?) AND status = 'ACTIVE' ORDER BY tenant_id, id", ROW,
                SYSTEM_TENANT, tenantId).stream().map(Template::view).toList();
    }

    /** 管理端：tenantId 为 null 列全部。 */
    public List<Map<String, Object>> all(Long tenantId) {
        var rows = tenantId == null ? jdbc.query(SELECT + " ORDER BY tenant_id, id", ROW)
                : jdbc.query(SELECT + " WHERE tenant_id IN (?, ?) ORDER BY tenant_id, id", ROW, SYSTEM_TENANT, tenantId);
        return rows.stream().map(Template::view).toList();
    }

    /** 发起转码时解析模板：name 为空用默认模板；只认系统模板和本租户的 ACTIVE 模板，别人的模板当不存在（404）。 */
    public Template resolve(long tenantId, String name) {
        String wanted = name == null || name.isBlank() ? defaultName : name.trim();
        var rows = jdbc.query(SELECT + " WHERE tenant_id IN (?, ?) AND name = ? AND status = 'ACTIVE' ORDER BY tenant_id DESC",
                ROW, SYSTEM_TENANT, tenantId, wanted);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        return rows.get(0);
    }

    public String defaultName() {
        return defaultName;
    }

    @Transactional
    public Map<String, Object> create(long tenantId, Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("body required");
        Object rawName = body.get("name");
        if (!(rawName instanceof String name) || !TEMPLATE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name must match [a-z0-9_]{1,32}");
        }
        Integer clash = jdbc.queryForObject("SELECT COUNT(*) FROM vod_transcode_template WHERE name = ? AND tenant_id IN (?, ?)",
                Integer.class, name, SYSTEM_TENANT, tenantId);
        if (clash != null && clash > 0) throw new UploadService.ConflictException("template name already used");
        checkCodec(body);
        String ladder = ladderJson(body.get("ladder"));
        int audio = audioKbps(body.get("audio_kbps"), 96);
        String description = description(body.get("description"));
        long id = insert("INSERT INTO vod_transcode_template(tenant_id, name, description, ladder_json, audio_kbps) VALUES (?,?,?,?,?)",
                tenantId, name, description, ladder, audio);
        return byId(id).view();
    }

    /** 可改 description、ladder、audio_kbps、status（ACTIVE/DISABLED）。改动只影响之后领取的任务。 */
    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body) {
        Template t = byId(id);
        if (t.tenantId() == SYSTEM_TENANT && !allowSystemEdit) {
            throw new UploadService.ConflictException("system templates are read-only (vod.template.allow-system-edit=false)");
        }
        if (body == null) body = Map.of();
        checkCodec(body);
        String ladder = body.containsKey("ladder") ? ladderJson(body.get("ladder")) : t.ladderJson();
        int audio = body.containsKey("audio_kbps") ? audioKbps(body.get("audio_kbps"), t.audioKbps()) : t.audioKbps();
        String description = body.containsKey("description") ? description(body.get("description")) : t.description();
        String status = t.status();
        if (body.containsKey("status")) {
            Object s = body.get("status");
            if (!"ACTIVE".equals(s) && !"DISABLED".equals(s)) throw new IllegalArgumentException("status must be ACTIVE or DISABLED");
            if (t.tenantId() == SYSTEM_TENANT && t.name().equals(defaultName) && "DISABLED".equals(s)) {
                throw new UploadService.ConflictException("default template cannot be disabled");
            }
            status = (String) s;
        }
        jdbc.update("UPDATE vod_transcode_template SET ladder_json = ?, audio_kbps = ?, description = ?, status = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?", ladder, audio, description, status, id);
        return byId(id).view();
    }

    public Template byId(long id) {
        var rows = jdbc.query(SELECT + " WHERE id = ?", ROW, id);
        if (rows.isEmpty()) throw new TenantAdminService.NotFound("template not found");
        return rows.get(0);
    }

    private static void checkCodec(Map<String, Object> body) {
        Object codec = body.get("video_codec");
        if (codec != null && !"h264".equals(codec)) throw new IllegalArgumentException("only video_codec=h264 is supported");
        Object container = body.get("container");
        if (container != null && !"hls_ts".equals(container)) throw new IllegalArgumentException("only container=hls_ts is supported");
    }

    private static String description(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String s) || s.length() > 200) throw new IllegalArgumentException("description must be a string up to 200 chars");
        return s;
    }

    private static int audioKbps(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (!(raw instanceof Integer v) || v < 32 || v > 320) throw new IllegalArgumentException("audio_kbps must be an integer in 32..320");
        return v;
    }

    /**
     * 梯队校验后序列化成 Worker 读的格式：[[档名, 短边, kbps], ...]。接受同样的数组，或 [{"name","short_edge","kbps"}]。
     * 范围只是输入合法性检查（偶数短边 144..2160、码率 100..20000 kbps、1..6 档、档名形如 720p 且不重复），不是性能建议。
     */
    static String ladderJson(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 6) {
            throw new IllegalArgumentException("ladder must be a list of 1..6 rungs");
        }
        List<List<Object>> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object item : list) {
            Object name, edge, kbps;
            if (item instanceof List<?> rung && rung.size() == 3) {
                name = rung.get(0);
                edge = rung.get(1);
                kbps = rung.get(2);
            } else if (item instanceof Map<?, ?> rung) {
                name = rung.get("name");
                edge = rung.get("short_edge");
                kbps = rung.get("kbps");
            } else {
                throw new IllegalArgumentException("each rung is [name, short_edge, kbps]");
            }
            if (!(name instanceof String n) || !RUNG_NAME.matcher(n).matches() || !names.add(n)) {
                throw new IllegalArgumentException("rung name must look like 720p and be unique");
            }
            if (!(edge instanceof Integer e) || e < 144 || e > 2160 || e % 2 != 0) {
                throw new IllegalArgumentException("short_edge must be an even integer in 144..2160");
            }
            if (!(kbps instanceof Integer k) || k < 100 || k > 20000) {
                throw new IllegalArgumentException("kbps must be an integer in 100..20000");
            }
            out.add(List.of(n, e, k));
        }
        try {
            String json = JSON.writeValueAsString(out);
            if (json.length() > 1000) throw new IllegalArgumentException("ladder too long");
            return json;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("bad ladder");
        }
    }

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return UploadService.generatedId(keys);
    }

    public static JsonNode parse(String json) throws JsonProcessingException {
        return JSON.readTree(json);
    }
}
