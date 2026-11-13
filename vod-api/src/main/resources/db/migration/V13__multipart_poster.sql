-- Phase 6 第一刀：Multipart 上传会话与转码封面。只追加列和索引，不改 V1–V12。

-- 上传方式：put（默认，旧会话都是这个）/ multipart。列名不用 mode，避开 MySQL 关键字。
ALTER TABLE vod_upload_session ADD COLUMN upload_mode VARCHAR(16) NOT NULL DEFAULT 'put';
-- 对象存储（S3 / MinIO）给的 multipart upload id；put 会话为 NULL。
ALTER TABLE vod_upload_session ADD COLUMN multipart_upload_id VARCHAR(1024) NULL;
-- 过期清理的条件更新领取：领取到这个时间为止，多实例时别的实例跳过；失败不清空，过期后再领。
ALTER TABLE vod_upload_session ADD COLUMN cleanup_until TIMESTAMP NULL;
-- 会话最后一次状态变化（COMPLETED / ABORTED）；旧行为 NULL。
ALTER TABLE vod_upload_session ADD COLUMN updated_at TIMESTAMP NULL;
CREATE INDEX idx_upload_session_status_expires ON vod_upload_session (status, expires_at);

-- 封面对象键：落在当期产物前缀 assets/v1/{asset}/{job}-{claim_version}/ 里（如 .../poster.jpg），
-- Worker 在写 hls_prefix / READY / 前缀 ACTIVE 的同一事务里写入；没有封面为 NULL。旧资产都是 NULL。
ALTER TABLE vod_media_asset ADD COLUMN poster_key VARCHAR(512) NULL;
