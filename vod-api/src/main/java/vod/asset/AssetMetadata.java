package vod.asset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vod.tenant.TenantGuard;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 路线 B：资产的标题与标签。标题可空、最长 200 字；标签每个 1–32 个字符（字母、数字、下划线、点、连字符，
 * 支持中文等任意文字的字母），每个资产最多 {@value #MAX_TAGS} 个，去重后按给出的顺序保存。
 */
@Service
public class AssetMetadata {
    public static final int MAX_TITLE = 200;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG = 32;
    private static final Pattern TAG = Pattern.compile("[\\p{L}\\p{N}_.\\-]{1," + MAX_TAG + "}");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public AssetMetadata(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /** 请求里的标题：null 表示没给；空串表示清空（返回 Optional 语义交给调用方：这里空串转 null）。 */
    public static String title(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) throw new IllegalArgumentException("title must be a string");
        String t = s.strip();
        if (t.length() > MAX_TITLE) throw new IllegalArgumentException("title is longer than " + MAX_TITLE + " characters");
        return t.isEmpty() ? null : t;
    }

    public static List<String> tags(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("tags must be an array of strings");
        var out = new LinkedHashSet<String>();
        for (Object item : list) {
            if (!(item instanceof String s)) throw new IllegalArgumentException("tags must be an array of strings");
            String t = s.strip();
            if (!TAG.matcher(t).matches()) {
                throw new IllegalArgumentException("invalid tag " + quote(t) + ": 1-" + MAX_TAG
                        + " letters, digits, '_', '.', '-'");
            }
            out.add(t);
        }
        if (out.size() > MAX_TAGS) throw new IllegalArgumentException("at most " + MAX_TAGS + " tags");
        return List.copyOf(out);
    }

    /** 筛选参数里的单个标签，规则同上；空白视为没给。 */
    public static String tagFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.strip();
        if (!TAG.matcher(t).matches()) throw new IllegalArgumentException("invalid tag filter");
        return t;
    }

    /** 在调用方事务里写标题和标签（建资产时用）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void initial(long tenantId, long assetId, String title, List<String> tags) {
        if (title != null) jdbc.update("UPDATE vod_media_asset SET title = ? WHERE id = ? AND tenant_id = ?", title, assetId, tenantId);
        replaceTags(tenantId, assetId, tags);
    }

    /**
     * PATCH /api/v1/assets/{id}：body 里出现的字段才改。title 为 null 或空串表示清空；tags 给数组表示整体替换。
     * 不存在、别的租户或已删除：404。
     */
    @Transactional
    public void update(long tenantId, long assetId, Map<String, Object> body) {
        var rows = jdbc.query("SELECT tenant_id, status FROM vod_media_asset WHERE id = ? FOR UPDATE",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, assetId);
        if (rows.isEmpty()) throw new TenantGuard.ResourceNotVisibleException();
        TenantGuard.requireVisible(tenantId, (Long) rows.get(0)[0]);
        if (AssetLifecycle.DELETED.equals(rows.get(0)[1])) throw new TenantGuard.ResourceNotVisibleException();
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("nothing to update: give title and/or tags");
        for (String key : body.keySet()) {
            if (!key.equals("title") && !key.equals("tags")) throw new IllegalArgumentException("unknown field " + key);
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (body.containsKey("title")) {
            jdbc.update("UPDATE vod_media_asset SET title = ?, updated_at = ? WHERE id = ?", title(body.get("title")), now, assetId);
        }
        if (body.containsKey("tags")) {
            if (body.get("tags") == null) throw new IllegalArgumentException("tags must be an array of strings");
            replaceTags(tenantId, assetId, tags(body.get("tags")));
            jdbc.update("UPDATE vod_media_asset SET updated_at = ? WHERE id = ?", now, assetId);
        }
    }

    private void replaceTags(long tenantId, long assetId, List<String> tags) {
        jdbc.update("DELETE FROM vod_asset_tag WHERE asset_id = ?", assetId);
        for (String tag : tags) {
            jdbc.update("INSERT INTO vod_asset_tag(tenant_id, asset_id, tag) VALUES (?,?,?)", tenantId, assetId, tag);
        }
    }

    /** 一批资产的标签，按插入顺序。 */
    public Map<Long, List<String>> tagsOf(Collection<Long> assetIds) {
        var out = new LinkedHashMap<Long, List<String>>();
        if (assetIds.isEmpty()) return out;
        for (long id : assetIds) out.put(id, new ArrayList<>());
        named.query("SELECT asset_id, tag FROM vod_asset_tag WHERE asset_id IN (:ids) ORDER BY id",
                new MapSqlParameterSource("ids", assetIds),
                rs -> {
                    out.get(rs.getLong(1)).add(rs.getString(2));
                });
        return out;
    }

    private static String quote(String s) {
        return "'" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "'";
    }
}
