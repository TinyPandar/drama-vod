CREATE TABLE vod_tenant (
  id     BIGINT PRIMARY KEY AUTO_INCREMENT,
  name   VARCHAR(200) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE vod_api_credential (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id  BIGINT NOT NULL,
  key_hash   CHAR(64) NOT NULL,
  scopes     VARCHAR(200) NOT NULL,
  status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE KEY uk_key_hash (key_hash)
);

CREATE TABLE vod_media_asset (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id     BIGINT NOT NULL,
  status        VARCHAR(32) NOT NULL,
  source_bucket VARCHAR(64) NOT NULL,
  source_key    VARCHAR(512) NOT NULL,
  source_size   BIGINT NULL,
  encrypted     TINYINT NOT NULL DEFAULT 0,
  KEY idx_tenant (tenant_id)
);

CREATE TABLE vod_upload_session (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id        BIGINT NOT NULL,
  asset_id         BIGINT NOT NULL,
  idempotency_key  VARCHAR(80) NULL,
  status           VARCHAR(16) NOT NULL,
  UNIQUE KEY uk_tenant_idem (tenant_id, idempotency_key)
);

CREATE TABLE vod_transcode_job (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id      BIGINT NOT NULL,
  asset_id       BIGINT NOT NULL,
  profile        VARCHAR(32) NOT NULL,
  status         VARCHAR(16) NOT NULL,
  claim_version  INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_asset_profile (asset_id, profile)
);

CREATE TABLE vod_outbox (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id  BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload    VARCHAR(2000) NOT NULL,
  status     VARCHAR(16) NOT NULL
);
