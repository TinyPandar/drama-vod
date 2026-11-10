"""内容密钥的写入入口（路线图第 08 节）。vod-api 侧读取对应 vod.key.KeyProvider。

本阶段只有开发用的明文存储：旧 Demo 用 content_key（Phase 0 冻结），Core 用 vod_content_key（V10）。
两套资产 id 是独立自增，会撞号，所以绝不能混表。换 KMS / 信封加密时替换 PlaintextKeyStore，key 接口的 16 字节不变。
"""
import os

KEY_TABLE = {"core": "vod_content_key", "legacy": "content_key"}


def key_uri(origin, asset_id):
    """写进 m3u8 #EXT-X-KEY 的 URI。旧 Demo /keys/{id} 凭旧 token；Core /keys/v1/{id} 凭 Core 播放 token。"""
    asset_id = int(asset_id)
    return f"/keys/v1/{asset_id}" if origin == "core" else f"/keys/{asset_id}"


class PlaintextKeyStore:
    """INSERT IGNORE + 回读：重试复用同一把 key。"""

    def get_or_create(self, c, origin, asset_id):
        table = KEY_TABLE[origin]
        c.execute(f"INSERT IGNORE INTO {table}(asset_id, key_hex, iv_hex) VALUES(%s,%s,%s)",
                  (asset_id, os.urandom(16).hex(), os.urandom(16).hex()))
        c.execute(f"SELECT key_hex, iv_hex FROM {table} WHERE asset_id=%s", (asset_id,))
        return c.fetchone()
