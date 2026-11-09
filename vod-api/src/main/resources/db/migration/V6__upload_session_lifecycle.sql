ALTER TABLE vod_upload_session ADD COLUMN object_key VARCHAR(512) NULL;
ALTER TABLE vod_upload_session ADD COLUMN expires_at TIMESTAMP NULL;
ALTER TABLE vod_upload_session ADD COLUMN checksum VARCHAR(128) NULL;

UPDATE vod_upload_session SET object_key = (
  SELECT a.source_key FROM vod_media_asset a WHERE a.id = vod_upload_session.asset_id);

-- 旧会话没有记录过期时间：按迁移时刻视为到期，不留永不过期的 OPEN 会话。
-- 已经建过任务的会话重复 complete 仍返回原任务。
UPDATE vod_upload_session SET expires_at = CURRENT_TIMESTAMP WHERE expires_at IS NULL;
