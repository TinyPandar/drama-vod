-- 对象存储里的文件单独建表；资产通过 source_object_id 引用源对象。
-- vod_media_asset.source_bucket / source_key / source_size 继续双写：Python Worker 仍直接读这几列。
CREATE TABLE vod_storage_object (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id    BIGINT NOT NULL,
  bucket       VARCHAR(64) NOT NULL,
  object_key   VARCHAR(512) NOT NULL,
  size         BIGINT NULL,
  etag         VARCHAR(128) NULL,
  checksum     VARCHAR(128) NULL,
  content_type VARCHAR(128) NULL,
  role         VARCHAR(16) NOT NULL,
  UNIQUE KEY uk_bucket_key (bucket, object_key),
  KEY idx_tenant_role (tenant_id, role)
);

ALTER TABLE vod_media_asset ADD COLUMN source_object_id BIGINT NULL;

-- 旧资产回填成 role=SOURCE 的对象行，大小沿用 source_size（未 complete 的为 NULL）。
INSERT INTO vod_storage_object(tenant_id, bucket, object_key, size, role)
SELECT tenant_id, source_bucket, source_key, source_size, 'SOURCE' FROM vod_media_asset;

UPDATE vod_media_asset SET source_object_id = (
  SELECT o.id FROM vod_storage_object o
  WHERE o.bucket = vod_media_asset.source_bucket
    AND o.object_key = vod_media_asset.source_key
    AND o.role = 'SOURCE');
