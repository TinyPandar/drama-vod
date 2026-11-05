-- 短剧点播 MVP 表结构。原则：元数据进 MySQL，大文件只存对象存储的 key。
SET NAMES utf8mb4;
USE vod;

-- 剧
CREATE TABLE drama (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  title          VARCHAR(200) NOT NULL,
  cover_key      VARCHAR(512) NULL,
  total_episodes INT NOT NULL DEFAULT 0,
  status         ENUM('DRAFT','ONLINE','OFFLINE') NOT NULL DEFAULT 'ONLINE',
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 集：一集当前对外播放哪个视频资产由 asset_id 决定（换片 = 改指针，不动旧文件）
CREATE TABLE episode (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  drama_id    BIGINT NOT NULL,
  ep_no       INT NOT NULL,
  title       VARCHAR(200) NOT NULL DEFAULT '',
  is_paid     TINYINT NOT NULL DEFAULT 0,
  price_coins INT NOT NULL DEFAULT 0,
  asset_id    BIGINT NULL,
  status      ENUM('DRAFT','READY','OFFLINE') NOT NULL DEFAULT 'DRAFT',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_drama_ep (drama_id, ep_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 视频资产：一次上传 = 一个资产。原片位置 + 转码产物位置 + 探测到的元信息
CREATE TABLE video_asset (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  episode_id    BIGINT NOT NULL,
  source_bucket VARCHAR(64) NOT NULL,
  source_key    VARCHAR(512) NOT NULL,
  source_size   BIGINT NULL,
  duration_ms   INT NULL,
  width         INT NULL,
  height        INT NULL,
  hls_prefix    VARCHAR(512) NULL,          -- 例如 assets/12/
  encrypted     TINYINT NOT NULL DEFAULT 0, -- 1 = HLS AES-128
  status        ENUM('UPLOADING','UPLOADED','TRANSCODING','READY','FAILED') NOT NULL DEFAULT 'UPLOADING',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_episode (episode_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 每个资产实际产出的码率档
CREATE TABLE asset_rendition (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  asset_id     BIGINT NOT NULL,
  name         VARCHAR(16) NOT NULL,        -- 720p / 540p / 360p
  width        INT NOT NULL,
  height       INT NOT NULL,
  video_kbps   INT NOT NULL,
  playlist_key VARCHAR(512) NOT NULL,
  UNIQUE KEY uk_asset_name (asset_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 转码任务：(asset_id, profile) 唯一 => 重复提交/重复消费都落到同一行，天然幂等
CREATE TABLE transcode_job (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  asset_id     BIGINT NOT NULL,
  profile      VARCHAR(32) NOT NULL DEFAULT 'ladder_v1',
  status       ENUM('PENDING','RUNNING','RETRYING','SUCCEEDED','FAILED') NOT NULL DEFAULT 'PENDING',
  attempts     INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  locked_until DATETIME NULL,               -- 租约：worker 挂了，过期后别人可以接手
  worker_id    VARCHAR(64) NULL,
  trace_id     VARCHAR(64) NULL,
  last_error   TEXT NULL,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_asset_profile (asset_id, profile),
  KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 内容密钥（演示直接存明文 hex；生产应由 KMS 加密后存密文，即"信封加密"）
CREATE TABLE content_key (
  asset_id   BIGINT PRIMARY KEY,
  key_hex    CHAR(32) NOT NULL,
  iv_hex     CHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户解锁记录（购买/会员/看广告解锁都落这里）；(user_id, episode_id) 唯一防重复扣费
CREATE TABLE user_unlock (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT NOT NULL,
  episode_id BIGINT NOT NULL,
  source     ENUM('PURCHASE','VIP','AD') NOT NULL DEFAULT 'PURCHASE',
  order_no   VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_ep (user_id, episode_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 播放计数落库（Redis 实时 INCR，后台定时批量刷到这里）
CREATE TABLE episode_play_stat (
  episode_id BIGINT PRIMARY KEY,
  play_count BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
