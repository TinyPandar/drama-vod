-- 路线 B：资产生命周期。只追加列、表和索引，不改 V1–V13。

-- 资产元数据。created_at 对已有行取迁移时刻（此前没有记录创建时间）。
ALTER TABLE vod_media_asset ADD COLUMN title VARCHAR(200) NULL;
ALTER TABLE vod_media_asset ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE vod_media_asset ADD COLUMN updated_at TIMESTAMP NULL;
-- 软删除：status='DELETED'，deleted_reason 为 USER（租户删除）/ UPLOAD_ABORTED（取消上传）/ UPLOAD_EXPIRED（上传过期被清理）。
ALTER TABLE vod_media_asset ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE vod_media_asset ADD COLUMN deleted_reason VARCHAR(32) NULL;
CREATE INDEX idx_asset_tenant_status ON vod_media_asset (tenant_id, status, id);

-- 标签：每个资产最多若干个，(tenant_id, tag) 上建索引供筛选。
CREATE TABLE vod_asset_tag (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  asset_id  BIGINT NOT NULL,
  tag       VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_asset_tag (asset_id, tag),
  KEY idx_asset_tag_tenant (tenant_id, tag, asset_id)
);

-- 源对象删除：delete_after 到期后由 vod-api 的 StorageObjectCleanup 删掉存储里的对象并写 deleted_at；
-- cleanup_until 是多实例领取（与 vod_upload_session.cleanup_until 同一做法）。
ALTER TABLE vod_storage_object ADD COLUMN delete_after TIMESTAMP NULL;
ALTER TABLE vod_storage_object ADD COLUMN deleted_at TIMESTAMP NULL;
ALTER TABLE vod_storage_object ADD COLUMN cleanup_until TIMESTAMP NULL;
CREATE INDEX idx_storage_object_delete ON vod_storage_object (deleted_at, delete_after);

-- 删资产时按 asset_id 找上传会话。
CREATE INDEX idx_upload_session_asset ON vod_upload_session (asset_id);

-- 保留期清理按时间扫描用的索引。
CREATE INDEX idx_webhook_delivery_status_created ON vod_webhook_delivery (status, created_at);
CREATE INDEX idx_webhook_event_status_created ON vod_webhook_event (status, created_at);
CREATE INDEX idx_outbox_status_published ON vod_outbox (status, published_at);
CREATE INDEX idx_idempotency_created ON vod_idempotency_key (created_at);
