-- Phase 4：Core 每次 claim 的产物写到 assets/v1/{asset_id}/{job_id}-{claim_version}/，这里记录每个前缀的生命周期。
-- WRITING：Worker 上传前插入；ACTIVE：成功事务里切换（同时 vod_media_asset.hls_prefix 指向它）；
-- GARBAGE：被替换、失败、取消、围栏丢失；DELETED：Worker 的清理线程过了宽限期后删完对象。
-- Phase 4 之前的固定前缀 assets/v1/{asset_id}/ 和旧 Demo 的 assets/{id}/ 没有行，清理永远不碰。
CREATE TABLE vod_output_prefix (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  asset_id      BIGINT NOT NULL,
  job_id        BIGINT NOT NULL,
  claim_version INT NOT NULL,
  prefix        VARCHAR(512) NOT NULL,
  status        VARCHAR(16) NOT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_output_prefix (prefix),
  KEY idx_output_asset_status (asset_id, status),
  KEY idx_output_job (job_id),
  KEY idx_output_status_updated (status, updated_at)
);
