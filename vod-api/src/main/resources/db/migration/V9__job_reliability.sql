-- Phase 3：转码任务可靠性。只加 vod_* 表的列；旧 Demo 的 transcode_job / video_asset 不动。
-- error_code：失败分类（见 worker/retry_policy.py），last_error 继续存原始文本。
-- next_attempt_at：RETRYING 任务的下次重试时间，sweeper 用它兜底丢失的延迟重试消息。
ALTER TABLE vod_transcode_job ADD COLUMN error_code VARCHAR(64) NULL;
ALTER TABLE vod_transcode_job ADD COLUMN next_attempt_at TIMESTAMP NULL;

-- OutboxPublisher 多实例：条件 UPDATE 领取，locked_until 过期后别的实例可以接手。
-- created_at 给 outbox 积压指标用；已有行取迁移时刻。
ALTER TABLE vod_outbox ADD COLUMN locked_until TIMESTAMP NULL;
ALTER TABLE vod_outbox ADD COLUMN locked_by VARCHAR(100) NULL;
ALTER TABLE vod_outbox ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 积压指标、sweeper、outbox 轮询都按 status 过滤。
CREATE INDEX idx_job_status_updated ON vod_transcode_job(status, updated_at);
CREATE INDEX idx_outbox_status_created ON vod_outbox(status, created_at);
