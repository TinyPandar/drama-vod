CREATE TABLE vod_webhook_endpoint (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id  BIGINT NOT NULL,
  url        VARCHAR(500) NOT NULL,
  secret     VARCHAR(80) NOT NULL,
  events     VARCHAR(200) NOT NULL,
  status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE vod_webhook_delivery (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id     BIGINT NOT NULL,
  endpoint_id   BIGINT NOT NULL,
  event_type    VARCHAR(64) NOT NULL,
  status        VARCHAR(16) NOT NULL,
  attempts      INT NOT NULL DEFAULT 0,
  response_code INT NULL
);

CREATE TABLE vod_usage_daily (
  tenant_id       BIGINT NOT NULL,
  usage_day       CHAR(10) NOT NULL,
  storage_bytes   BIGINT NOT NULL DEFAULT 0,
  play_requests   BIGINT NOT NULL DEFAULT 0,
  transcode_jobs  INT NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, usage_day)
);
