-- Phase 5：平台化控制台。只新增表、列和索引，不改 V1–V11，不动 uk_asset_profile。
-- 注释和字符串里不要写分号和两个连字符：scripts/test_fencing_mysql.py 用简单拆分在真实 MySQL 上重放迁移。

-- 管理端用户。只存 BCrypt 哈希；开发环境用 VOD_ADMIN_USERNAME / VOD_ADMIN_PASSWORD 种子（见 AdminUserSeeder）。
CREATE TABLE vod_admin_user (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  username      VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at TIMESTAMP NULL,
  UNIQUE KEY uk_admin_username (username)
);

-- 租户配额：NULL 时取配置 vod.quota.default-*，小于等于 0 表示不限制。
-- max_concurrent_jobs：同时未结束（PENDING/RETRYING/RUNNING）的 Core 任务数上限。
-- max_daily_transcodes：每天新建（complete）、重新转码、重试的总次数上限，计数沿用 vod_usage_daily.transcode_jobs。
ALTER TABLE vod_tenant ADD COLUMN max_concurrent_jobs INT NULL;
ALTER TABLE vod_tenant ADD COLUMN max_daily_transcodes INT NULL;
ALTER TABLE vod_tenant ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- API Key 管理：明文只在创建/轮换响应里出现一次。key_prefix 是明文前几位，只用于界面辨认。
ALTER TABLE vod_api_credential ADD COLUMN name VARCHAR(100) NULL;
ALTER TABLE vod_api_credential ADD COLUMN key_prefix VARCHAR(16) NULL;
ALTER TABLE vod_api_credential ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE vod_api_credential ADD COLUMN revoked_at TIMESTAMP NULL;
CREATE INDEX idx_credential_tenant ON vod_api_credential(tenant_id);

-- 转码模板。tenant_id = 0 是系统模板。name 就是任务的 profile（uk_asset_profile 不变）。
-- ladder_json：[[档名, 短边像素, 视频码率kbps], ...]。编码器与封装目前只支持 h264 + HLS TS。
CREATE TABLE vod_transcode_template (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  name        VARCHAR(32) NOT NULL,
  description VARCHAR(200) NULL,
  video_codec VARCHAR(16) NOT NULL DEFAULT 'h264',
  container   VARCHAR(16) NOT NULL DEFAULT 'hls_ts',
  ladder_json VARCHAR(1000) NOT NULL,
  audio_kbps  INT NOT NULL DEFAULT 96,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_template_tenant_name (tenant_id, name)
);

-- 默认模板：参数与 Phase 0 冻结、worker.py 里写死的梯队一致（720/2500、540/1200、360/600，AAC 96k）。
INSERT INTO vod_transcode_template(tenant_id, name, description, ladder_json, audio_kbps)
VALUES (0, 'ladder_v1', 'default ladder 720p/540p/360p', '[["720p",720,2500],["540p",540,1200],["360p",360,600]]', 96);

-- 任务按 job_id 读模板（消息 v1 不变）。NULL 表示按 profile 找系统模板，找不到用 Worker 内置梯队。
ALTER TABLE vod_transcode_job ADD COLUMN template_id BIGINT NULL;
CREATE INDEX idx_job_tenant_status ON vod_transcode_job(tenant_id, status);

-- 重新转码、重试的 Idempotency-Key。fingerprint 记录请求目标，同一 key 换目标返回 409。
CREATE TABLE vod_idempotency_key (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id   BIGINT NOT NULL,
  operation   VARCHAR(32) NOT NULL,
  idem_key    VARCHAR(80) NOT NULL,
  fingerprint VARCHAR(200) NOT NULL,
  job_id      BIGINT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_idempotency (tenant_id, operation, idem_key)
);

-- Webhook 事件：成功、失败由 Worker 在写任务终态的同一事务里插入，取消由 vod-api 的取消事务插入，
-- transcode.requested 由建任务的事务插入。不用 vod_outbox（那张表的 PENDING 行会被发到转码队列）。
-- payload 由 vod-api 分发时生成一次并固定，之后每次重试签同一份字节。
CREATE TABLE vod_webhook_event (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id     BIGINT NOT NULL,
  event_type    VARCHAR(64) NOT NULL,
  job_id        BIGINT NULL,
  asset_id      BIGINT NULL,
  claim_version INT NULL,
  error_code    VARCHAR(64) NULL,
  reason        VARCHAR(64) NULL,
  payload       VARCHAR(2000) NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  locked_until  TIMESTAMP NULL,
  locked_by     VARCHAR(100) NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  dispatched_at TIMESTAMP NULL,
  KEY idx_webhook_event_status (status, id)
);

ALTER TABLE vod_webhook_endpoint ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE vod_webhook_endpoint ADD COLUMN disabled_at TIMESTAMP NULL;
CREATE INDEX idx_webhook_endpoint_tenant ON vod_webhook_endpoint(tenant_id, status);

-- 投递记录在 V3 的表上加列。旧行（event_id 为 NULL，同步投递时代写入）只用于查询，不会被投递循环领取。
ALTER TABLE vod_webhook_delivery ADD COLUMN event_id BIGINT NULL;
ALTER TABLE vod_webhook_delivery ADD COLUMN next_attempt_at TIMESTAMP NULL;
ALTER TABLE vod_webhook_delivery ADD COLUMN locked_until TIMESTAMP NULL;
ALTER TABLE vod_webhook_delivery ADD COLUMN locked_by VARCHAR(100) NULL;
ALTER TABLE vod_webhook_delivery ADD COLUMN last_error VARCHAR(500) NULL;
ALTER TABLE vod_webhook_delivery ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE vod_webhook_delivery ADD COLUMN updated_at TIMESTAMP NULL;
ALTER TABLE vod_webhook_delivery ADD COLUMN delivered_at TIMESTAMP NULL;
CREATE UNIQUE INDEX uk_delivery_event_endpoint ON vod_webhook_delivery(event_id, endpoint_id);
CREATE INDEX idx_delivery_status_next ON vod_webhook_delivery(status, next_attempt_at);
CREATE INDEX idx_delivery_tenant ON vod_webhook_delivery(tenant_id, id);
