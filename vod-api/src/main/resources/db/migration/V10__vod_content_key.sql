-- Phase 4：Core 加密资产的内容密钥单独建表。
-- 旧 Demo 的 content_key 用 video_asset.id 做主键，和 vod_media_asset.id 是两套自增，会撞号；
-- Core 资产从这里取 key，content_key 保持旧 Demo 专用（Phase 0 冻结，不迁移）。
-- asset_id 逻辑上指向 vod_media_asset.id；和现有表一样不建外键，避免测试与运维清表时的删除顺序依赖。
-- key_hex 明文只给开发用的 KeyProvider；换 KMS / 信封加密时 key 接口的 16 字节响应不变。
CREATE TABLE vod_content_key (
  asset_id   BIGINT PRIMARY KEY,
  key_hex    CHAR(32) NOT NULL,
  iv_hex     CHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
