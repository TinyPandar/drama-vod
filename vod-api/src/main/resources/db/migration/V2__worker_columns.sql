ALTER TABLE vod_transcode_job ADD COLUMN attempts INT NOT NULL DEFAULT 0;
ALTER TABLE vod_transcode_job ADD COLUMN max_attempts INT NOT NULL DEFAULT 3;
ALTER TABLE vod_transcode_job ADD COLUMN locked_until TIMESTAMP NULL;
ALTER TABLE vod_transcode_job ADD COLUMN worker_id VARCHAR(64) NULL;
ALTER TABLE vod_transcode_job ADD COLUMN last_error VARCHAR(2000) NULL;
ALTER TABLE vod_transcode_job ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE vod_media_asset ADD COLUMN hls_prefix VARCHAR(512) NULL;
ALTER TABLE vod_media_asset ADD COLUMN duration_ms INT NULL;
ALTER TABLE vod_media_asset ADD COLUMN width INT NULL;
ALTER TABLE vod_media_asset ADD COLUMN height INT NULL;
