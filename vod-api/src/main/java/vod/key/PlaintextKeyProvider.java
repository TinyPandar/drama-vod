package vod.key;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.Optional;

/** 开发用：key_hex 明文存在 vod_content_key（V10）。不读旧 Demo 的 content_key，两套资产 id 会撞号。 */
@Component
public class PlaintextKeyProvider implements KeyProvider {
    private final JdbcTemplate jdbc;

    public PlaintextKeyProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<byte[]> contentKey(long assetId) {
        var rows = jdbc.queryForList("SELECT key_hex FROM vod_content_key WHERE asset_id = ?", String.class, assetId);
        if (rows.isEmpty() || rows.get(0) == null) return Optional.empty();
        byte[] key;
        try {
            key = HexFormat.of().parseHex(rows.get(0).trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return key.length == 16 ? Optional.of(key) : Optional.empty();
    }
}
