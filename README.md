# drama-vod：短剧点播示例

Docker Compose 点播系统：VOD Core 视频云（Java 21 + Spring Boot 控制面 + Python/FFmpeg Worker）和它的第一个客户——短剧 Demo（FastAPI，负责剧集、观众账号、金币解锁和播放页）。路线 C 起 Demo 的上传、转码、播放都经视频云（见下文「路线 C」），旧的 Demo 转码链路已停用；管理控制台调用 `/api/v1`。

媒体流程：**预签名直传 → RabbitMQ → FFmpeg 多码率 HLS → Nginx 签名校验与缓存 → hls.js 播放**。HLS 桶保持私有，API 验证回源密钥后为 Nginx 提供对象。旧 Demo 的付费集使用 AES-128 HLS。MySQL 保存数据，Redis 提供热点缓存和播放计数，Prometheus 是可选监控。

路线图见 [docs/panda-vod-roadmap.html](docs/panda-vod-roadmap.html)，旧接口兼容约定见 [docs/phase-0-contract.md](docs/phase-0-contract.md)。

## 路线 C：Demo 迁到视频云（`plan/demo-on-core`，从 `plan/asset-lifecycle` 叠出，未合并）

### 为什么

路线图把短剧站定为视频云的第一个内部客户，但此前 Demo 的上传、转码、播放和付费集仍走自己的旧链路（FastAPI 直接建任务、旧 `/hls/`、旧 `/keys/{id}`），视频云那条路只是旁路；观众身份是调用方随便传的 `user_id`，解锁不扣费，等于不设防；Core 的加密资产在 iPhone 原生播放器上取不到 key。

### 做了什么

**观众账号与金币**（`api/accounts.py`、`api/main.py`）

- `POST /api/auth/register`、`POST /api/auth/login`：用户名 3–32 位、口令至少 8 位；口令 PBKDF2-HMAC-SHA256（每人随机盐）；返回会话 token `dsess1.…`（HMAC，密钥 `DEMO_SESSION_SECRET`，有效期 `DEMO_SESSION_TTL_SECONDS` 默认 7 天）。新用户送 `DEMO_SIGNUP_COINS`（默认 100）演示金币。同一用户名登录失败过多 429。
- `GET /api/me`：当前观众、余额、已解锁的集。
- `POST /api/users/{id}/unlock`：要本人的会话（没有 401、别人 403）。付费集在一个事务里写 `user_unlock`、条件扣 `price_coins`、记 `coin_ledger` 流水；重复解锁不重复扣；余额不够 402 `insufficient_coins`、什么都不写。
- 播放：观众取自会话；`?user_id=` 只在和会话一致时允许（否则 403），没有会话时不再算数（过渡开关 `DEMO_ALLOW_USER_ID_PARAM=1`）。匿名可看免费集，付费集 401。
- 新表（api 启动时 `CREATE TABLE IF NOT EXISTS`）：`demo_user`、`coin_ledger`、`episode_core_upload`；`episode` 追加 `core_asset_id`、`duration_ms`。`mysql/init.sql` 不变。

**每一集都在视频云上**

- 冻结的 `upload-url` / `complete` / `GET /api/assets/{id}` 改成 Core 的适配层：付费集上传成 Core 加密资产，标题和 `drama-{id}`、`episode-{id}` 标签写到 Core 资产上（控制台里能认出来）。播放页的 `vod-upload` / `vod-complete` 是同一件事。
- 对账线程（默认每 5 秒，`DEMO_SYNC_INTERVAL_SECONDS`）问 Core 每个等待中的上传：READY 就把这一集切到新资产（只往更新的资产切，同一集先后两次上传不会被先完成的那次覆盖）；失败或资产不见了就不再等。`GET /api/assets/{id}` 也会顺手对一次。
- 播放一律向视频云要 Core 播放 token：`master_url` 是 `/hls/v1/…`，加密集 key 走 `/keys/v1/{id}`，`encrypted` 取 Core 的真实值；下一集预加载同样经视频云。还没迁到 Core 的旧集暂时仍按旧链路播（`via: "legacy"`），迁完就没有了。
- 路线 C 之前经 `vod-upload` 接上的资产（`episode_vod_link`）启动时并入对账表，自动切换。

**iPhone 原生播放加密集**（vod-api `NativeHlsController`）

- `GET /api/v1/native-hls/{asset}/master.m3u8?token=` 和 `…/media.m3u8?rendition=&token=`：只认 Core 播放 token（不经租户 Key 过滤器）。现读存储里的播放列表并改写：子列表指回本接口，分片改成和 `master_url` 同一签名的绝对 `/hls/v1/` 地址（仍走 Nginx 签名与缓存），`#EXT-X-KEY` 的 URI 加 `?token=`。token 无效、过期、资产不匹配、资产不是 READY 一律 401；no-store。
- `playback-token` 响应新增 `native_master_url`；Demo 的 play 原样带出，播放页在不支持 hls.js 的浏览器（iPhone / Safari）上用它。

**收掉旧链路**

- Worker 默认不再处理旧 Demo 的转码消息（确认并丢弃），补偿扫描也不再重投旧任务；`WORKER_LEGACY_ENABLED=1` 可临时打开以排空遗留任务。Demo 不再自己建转码任务、发 MQ 消息（`contract_check.py` 改成检查这一点）。
- 迁移工具 `api/migrate_core.py`（在 api 容器里 `python migrate_core.py [--dry-run]`）：对还挂在旧资产上的 READY 剧集，在 Core 开上传会话、在源桶里服务端复制旧原片、调 complete；转码完成后由对账线程切换，切换前这一集照常按旧链路播。可重复运行。旧原片和旧 HLS 产物不删。
- 旧的读接口（`/hls/{sig}/…`、`/keys/{id}`、`/api/playback/{asset}/…`）保留，只为切换前已发出的地址服务。冻结条目的具体变化写在 [docs/phase-0-contract.md](docs/phase-0-contract.md) 末尾「路线 C」一节。

**播放页**（`nginx/html/index.html`）：页头的「观众编号」换成登录 / 注册和金币余额；付费集未登录提示登录，登录后自动重试；解锁写明价格、扣金币、余额不够有提示；观看页顶栏也有账号入口；拦截提示出现时顶栏仍可点（原来「返回」按钮会被遮住）；登录弹层改到页面根部（原来在观看页里打不开）。原生 HLS 用 `native_master_url`。

### 跑过的检查

| 检查 | 结果 |
| --- | --- |
| `mvn test`（H2 + MySQL 8.4） | 165 个，0 失败 0 跳过；新增 `NativeHlsTest` 4 个（改写结果与签名、固定前缀、各种 401、档位名校验） |
| 重写的 `scripts/e2e.py`（Demo 作为视频云客户） | 47/47：冻结上传接口落到 Core、没 PUT 就 complete 409、重复 complete 同一任务；对账切换；注册 / 登录 / 重名 409 / 口令错 401；匿名看免费集、付费集 401、只带 `user_id` 不算登录、未解锁 402、会话与 `user_id` 不符 403；不能替别人解锁、扣 30 金币、重复解锁不扣、余额不够 402；加密集 key 走 `/keys/v1/`、无 token 401、别的集的 token 401；**带 Authorization 头和 iPhone 原生方式（不带任何头）都用 ffmpeg 完整解码加密集**；篡改签名 403；下一集预加载；播放计数回刷 |
| 新增 `scripts/player_live.py`（播放页，真实 Chromium） | 11/11：匿名免费集真的在播；付费集提示登录 → 页头注册 → 自动重试提示 30 金币 → 解锁后加密集真的在播、余额扣 30；退出后又要求登录；无脚本错误 |
| `api/migrate_core.py` 在 A、B 留下的真实旧数据上 | 8 个旧剧集全部迁到 Core 并切换（付费集在 Core 是加密资产），第二次 `--dry-run` 显示 0 个待迁；`browser_check.py` 打开的第 1 集已从 Core 播放 |
| `drama_vod_live.py`、`core_live.py` | 全过（前者「未解锁 402」改为「未就绪 409」，付费规则改由 e2e 覆盖；后者改成可重复运行） |
| 回归：`playback_live`、`platform_live`、`media_live`、`lifecycle_live`、`console_live`、`fault_inject` 6 个场景 | 30/30、40/40、27/27、28/28、61/61、6/6 |
| `test_api_playback.py`（15 个，含新的账号与播放用例）、`test_vod_client.py`、`test_bridge.py`（旧链路默认停用）、`contract_check.py`、其余 `scripts/test_*.py`、`test_fencing_mysql.py` | 全过 |

CI 的 Compose live 工作流加了 `player_live.py` 和一次性的 `DEMO_SESSION_SECRET`。

### 已知边界

- 账号只有用户名口令，没有找回口令、邮箱或手机验证；金币只能注册时获得，没有充值。会话 token 不能单独吊销（只能换 `DEMO_SESSION_SECRET` 或等过期）。
- 对账靠轮询，没有用 Webhook：Demo 在内网（`http://api:8000`），Core 的 SSRF 检查会拒绝这种地址。换成 Webhook 需要 Demo 有公网 HTTPS 地址。
- 换片后旧的 Core 资产不会自动删除（可在控制台或用 `DELETE /api/v1/assets/{id}` 删）。
- 迁移只处理 READY 的旧剧集；还在转码中的旧任务要先 `WORKER_LEGACY_ENABLED=1` 排空。迁移中途失败的那一集，要等它的 Core 上传会话过期被清理（默认约 20 分钟）后再运行一次。
- 原生 HLS 改写在真 iPhone / Safari 上没有测过，只用 ffmpeg 不带请求头的方式验证了同一份播放列表。
- 重建 api 容器后 Nginx 要 `docker compose restart nginx`：Nginx 里旧 Demo 的 upstream 只在启动时解析一次（原来就如此，`/hls/` 回源的缓存与 Range 行为依赖它，这次没改）。

## 路线 B：资产生命周期（`plan/asset-lifecycle`，从 `plan/live-verify` 叠出，未合并）

### 为什么

此前资产只进不出：Core 没有任何删除接口，源文件和转码产物删不掉，存储用量只增不减；取消或过期的上传会留下源对象，资产永远停在 `UPLOADING`；Webhook 事件、投递、outbox、幂等键几张流水表没有保留期；资产没有标题、标签、创建时间，列表不能搜索筛选。

### 做了什么

**删除资产**：`DELETE /api/v1/assets/{id}`（scope `asset`）。

- 软删除：资产行保留，`status='DELETED'`，记 `deleted_at` 和 `deleted_reason`（`USER` / `UPLOAD_ABORTED` / `UPLOAD_EXPIRED`）。返回 `{id, status, deleted_at, deleted_reason}`；已删除再调同样 200（不重复退配额）；别的租户 404。
- 同一资产有任务在 `PENDING` / `RETRYING` / `RUNNING` 时 409，先取消再删。还 OPEN 的上传会话一并取消（multipart 在存储侧 abort，存储不可用时 503、什么都不改）。
- 删除后：详情、播放、播放 token、密钥接口、重新转码都 404；这个资产的任务重试 409；默认列表不再出现，`?status=DELETED` 能查到。任务行保留作历史。
- 存储配额退回 `source_size`（complete 时占用的那部分）。
- 转码产物：所有 `ACTIVE` / `WRITING` 输出前缀改成 `GARBAGE`、资产的 `hls_prefix` / `poster_key` 清空，由 Worker 现有的前缀清理在宽限期（`WORKER_OUTPUT_GC_GRACE_SECONDS`，且不小于租约）后删掉。Worker 没有改。
- 源文件：`vod_storage_object.delete_after` 到期后由新增的 `StorageObjectCleanup` 删除（默认删除后下一轮就删，`VOD_ASSET_SOURCE_DELETE_GRACE_SECONDS` 可调）。删前复核对象是 `SOURCE` 且所属资产确实已 `DELETED`，存储失败保留领取、过期后重试，多实例用条件 UPDATE 领取。
- 内容密钥行、标签一并删除。`scripts/dlq.py replay` 遇到已删除资产拒绝重放。
- 加锁顺序：会话 → 租户 → 任务 → 资产 → 前缀，和 complete、建任务、取消、Worker 成功事务不成环（真 MySQL 上 10 轮「删除 vs 重转」并发只有一方生效、不死锁）。

**没完成的上传收尾**（`UploadCleanup` 扩展）：

- 取消上传（`POST /uploads/{id}/abort`，put 和 multipart 都适用）后资产改为 `DELETED`（`UPLOAD_ABORTED`），已经 PUT 或已组合的源对象排进删除。原来资产会一直停在 `UPLOADING`。
- put 会话过期后再过 `VOD_UPLOAD_PUT_ABANDON_GRACE_SECONDS`（默认 300 秒，给过期前已开始的 PUT 留时间）收尾：会话 `ABORTED`、资产 `DELETED`（`UPLOAD_EXPIRED`）、源对象删除。put 会话过期后 complete 本来就 409，留下的对象不可能再被用到。
- multipart 没组合的：照旧在存储侧 abort，再做同样的收尾。
- multipart 已在存储侧组合、但一直没建任务的（N1）：过期后再保留 `VOD_UPLOAD_ASSEMBLED_RETENTION_SECONDS`（默认 7 天），期间仍可 complete；之后收尾、删对象。原来这种对象会一直留着。

**保留期清理**（新增 `RetentionCleanup`，默认每小时一轮，分批删）：

| 表 | 删什么 | 默认保留 |
| --- | --- | --- |
| `vod_webhook_delivery` | `DELIVERED` / `FAILED`，按创建时间 | 30 天（`VOD_RETENTION_WEBHOOK_DAYS`） |
| `vod_webhook_event` | `DISPATCHED`、按创建时间、且已没有投递行引用 | 同上 |
| `vod_outbox` | `PUBLISHED`，按发布时间 | 30 天（`VOD_RETENTION_OUTBOX_DAYS`） |
| `vod_idempotency_key` | 按创建时间 | 168 小时（`VOD_RETENTION_IDEMPOTENCY_HOURS`）；之后同一个 Idempotency-Key 当成新请求 |

`PENDING` 的行永远不删；保留期 `<= 0` 表示这张表不清理。

**标题、标签与搜索**：

- `POST /uploads` 可带 `title`（最长 200 字）和 `tags`（最多 10 个，每个 1–32 个字符：文字、数字、`_`、`.`、`-`，去重保序）；Idempotency-Key 重放不改已有资产。
- `PATCH /api/v1/assets/{id}`（scope `asset`）：body 里出现的字段才改；`title` 为空串或 null 表示清空；`tags` 整体替换；未知字段 400。返回改后的详情。
- `GET /api/v1/assets` 新增筛选：`status`（缺省不含 `DELETED`）、`q`（标题包含，`%` `_` 按字面匹配；纯数字时也匹配资产 id）、`tag`（精确）。列表和详情多了 `title`、`tags`、`created_at`，详情还有 `updated_at`。
- 迁移 `V14__asset_lifecycle.sql`：只追加列、表、索引。已有资产的 `created_at` 是迁移时刻（此前没有记录）。

**控制台**：上传卡片可填标题（留空用文件名）和标签；资产列表有关键字 / 状态 / 标签筛选，显示标题、标签和创建时间；资产详情显示标题和标签、可编辑，有「删除这个资产」（有进行中的任务时按钮不可点并说明原因，点了有确认框），删除后回列表并提示。状态 `DELETED` 显示为「已删除」。

**指标**：`vod_asset_deleted_total{reason}`、`vod_asset_source_delete_total{result}`、`vod_retention_deleted_total{table}`、`vod_upload_multipart_total{event}` 新增 `put_expired`、`assembled_expired`。

### 跑过的检查

| 检查 | 结果 |
| --- | --- |
| `mvn test`（H2 + MySQL 8.4，Flyway V1–V14） | 161 个，0 失败 0 跳过。新增 `AssetLifecycleTest` 13 个、`AssetLifecycleMysqlTest` 3 个（CI 里已列为必须真跑的 MySQL 用例）；`MultipartUploadTest` 里「取消后资产仍 UPLOADING」的断言改成 `DELETED` / `UPLOAD_ABORTED` |
| 新增 `scripts/lifecycle_live.py`（Compose 栈 + `docker-compose.lifecycle-live.yml`） | 28/28：标题、标签、筛选、PATCH；删除后各接口 404、存储用量退回、源文件和转码产物从 MinIO 删掉（清理后原播放地址 404）；有进行中的任务 409；取消上传和上传过期后资产 `DELETED`、源对象删除、过期后 complete 409 |
| `scripts/console_live.py`（加了路线 B 的界面步骤） | 61/61 |
| `e2e.py`、`playback_live.py`、`platform_live.py`、`media_live.py` 回归 | 30/30、30/30、40/40、27/27 |
| `test_fencing_mysql.py`、`contract_check.py` 与其余 `scripts/test_*.py` | 全过（`test_dlq.py` 新增「已删除资产拒绝重放」） |
| 已有数据的 MySQL 上执行 V14 | 在 A 留下的验收库（几十个资产）上升级，迁移成功 |

真 MySQL 测试抓到一个问题并已修：MySQL `TIMESTAMP`（无小数位）会把小数秒四舍五入，「现在」写进去可能变成下一秒，源对象清理就要多等一轮。排期时间截到整秒。

验收截图已按本分支的控制台重新生成（[docs/console-redesign/acceptance/](docs/console-redesign/acceptance/)，新增 `asset-detail-meta-*`）。

### 已知边界

- Phase 4 之前写的固定前缀 `assets/v1/{id}/`（没有输出前缀行）和旧 Demo 的 `assets/{id}/` 删资产时不会被清理；旧 Demo 本身没有删除接口（C 会收掉旧链路）。
- 删除后，Nginx 边缘缓存里的播放列表和分片在缓存有效期内仍可能被拿到；之后回源会失败。没有做 CDN 刷新。
- 软删除的资产行和任务行永久保留（体积很小），没有彻底删除的接口。
- 已删除资产没有 Webhook 事件（`asset.deleted` 未做）。
- `created_at` 对 V14 之前的资产不准确。

## 路线 A：在 Compose 栈上把账还清（`plan/live-verify`，未合并）

基线 `main` = `c102205`（控制台改版已合并）。目标：Phase 3–6 和控制台改版各节「没跑」清单里、需要整套 Docker Compose 的检查全部真跑一遍；跑出来的问题就地修；以后由 CI 自动跑。后续 B（资产生命周期）、C（Demo 迁到 Core）的分支从本分支往下叠，合并时请按 A → B → C 的顺序。

### 环境

- 2026-11-18，macOS 26.2 / Apple M4。开发机本来没有 Docker：用 colima 0.10.3（Lima 2.3.0，Apple Virtualization 虚拟机，4 核 / 6 GB）+ Docker CLI 29.10 + Compose 5.8，二进制都只放在仓库外的 `.phase3-tools/dockerkit/`，没有装进系统。引擎是 Linux 上的 Docker 29.5（虚拟机里），不是 Docker Desktop。
- 一个栈跑全部脚本：项目名 `drama-vod-live`，叠加 `docker-compose.platform-live.yml` 和 `docker-compose.media-live.yml`；`.env` 里的口令和密钥都是 `openssl rand -hex 32` 生成的一次性值，没有提交。
- 宿主访问 Maven Central 返回 403（本机代理），`mvn test` 改在 `maven:3.9-eclipse-temurin-21` 容器里跑；本机 Node 24 缺动态库，控制台在 `node:24` 容器里构建（与 CI 同版本）。

### 跑过的检查与结果

| 检查 | 结果 | 说明 |
| --- | --- | --- |
| `scripts/e2e.py`（旧 Demo） | 30/30 | 上传、转码、签名、付费集 AES-128、ffmpeg 完整解码、播放计数回刷 |
| `scripts/playback_live.py`（Core 播放） | 30/30 | Phase 4 时写着「Range 问题已知会有一项失败」，`fix/leftovers` 之后已全过 |
| `scripts/platform_live.py`（Phase 5） | 40/40 | 改了脚本两处，见下 |
| `scripts/media_live.py`（Phase 6） | 27/27 | 经 Nginx 边缘；chainguard MinIO 镜像下分片、续传、过期清理都正常 |
| `scripts/fault_inject.py` 全部 6 个场景 | 6/6 | kill、dup、minio、rabbitmq、cancel、gc；rabbitmq 场景改了脚本，另修了发布器，见下 |
| `scripts/core_live.py`、`scripts/drama_vod_live.py` | 全过 | 早期脚本假设在 Compose 网络里，用 host 网络的一次性 worker 容器跑（命令见下），脚本没改 |
| `scripts/kill_recover_submit.py` 手动流程 | 恢复成功 | 旧 Demo 路径转码中强杀 worker，另一个 worker 接手，`claim_version=2`、资产 READY。按默认 1800 秒租约领到的任务要等租约到期才被接手，也实测到了（到期后由补偿扫描完成） |
| `scripts/browser_check.py`（hls.js 真浏览器） | 播放正常 | 首帧 219 ms、自动切档、预加载下一集；脚本加了 `CHROME_BIN` 和 `--remote-allow-origins` |
| 新增 `scripts/console_live.py`（控制台真实后端验收） | 52/52 | 见「控制台验收」 |
| `scripts/test_fencing_mysql.py`（连 Compose 里的 MySQL 8.4） | 30/30 | |
| `vod-api` `mvn test`（MySQL 用例连 Compose MySQL） | 145 个，0 失败 0 跳过 | |
| `contract_check.py` 和其余 `scripts/test_*.py` | 全过 | |
| Prometheus（`--profile obs`） | 4 个目标都 up | `api`、`vod-api`、`rabbitmq`（`rabbitmq_prometheus` 的 per-object 端点）、`worker`（DNS 发现）；`vod_jobs_backlog`、`vod_transcode_jobs_total`、`rabbitmq_queue_messages_ready` 有数据 |

### 跑出来并修掉的问题

1. **RabbitMQ 重启后 vod-api 的发布连接泄漏**（`RabbitTranscodePublisher`）。原来用 amqp-client 默认的自动恢复连接，而每次发布都开一个临时 channel 并声明队列；RabbitMQ 重启后客户端去恢复这些早已关闭的 channel 上的队列，持续刷 `AlreadyClosedException`；同时 `isOpen()` 为假时代码又新建一条连接，旧的那条留着继续重试。改为关掉自动恢复，发布失败或确认超时就丢弃连接，下次发布重连。业务上原来也能最终发布（outbox 有退避重试），这次修的是泄漏和日志刷屏。修后 rabbitmq 场景里 outbox 行在恢复后按退避（2+4+8+16 秒）发布成功。
2. **控制台分片上传开始时闪出「分片上传中断」**（`AssetsView.vue`）。建好分片会话、第一行进度还没写出来的那一小段时间里，页面会显示橙色的「分片上传中断，还没传完」和灰掉的「继续上传」。改为上传进行中不显示这块提示。产物已在 `node:24` 里重新构建并提交。
3. **`platform_live.py` 起不来**：脚本里自定义的 `http()` 函数把 `http` 模块覆盖了，`http.server` 报 `AttributeError`。改成 `from http.server import …`。
4. **`platform_live.py` 重转断言有竞态**：重转后 Worker 可能在响应读回任务前就已领取，`claim_version` 是 +2 而不是 +1。断言改为接受 +1 或 +2（任务 id 不变的检查保留）。
5. **`fault_inject.py` 的 rabbitmq 场景有时序问题**：故障注入把 Worker 补偿扫描调得很短，资产会先被扫描兜底做完，脚本在 READY 的那一刻查 outbox，发布器还在退避中。改为 READY 后再等 outbox 变 PUBLISHED（新参数 `--outbox-wait-seconds`，默认 330，不小于 `vod.outbox.max-backoff-seconds`）。

### 控制台验收（原「实现说明」里没跑的第 1–5 条）

`scripts/console_live.py` 用 Playwright 驱动真实控制台，连真实后端，只通过界面操作（最后用无效身份直接请求接口，核对服务端仍拒绝）。52 项全部通过，覆盖：未登录跳登录页、口令错误中文提示、登录后回原页并高亮；未选 Key 时「资产」「用量与配额」出现引导且不含 401；建租户、停用 / 启用、改配额刷新后仍在；新建 Key 明文只出现一次、「在本标签页使用」后页头只有备注和前缀、整页不含明文；新建租户模板；小文件直传；大文件分片在第 2 片持续失败时出现续传提示、点「继续上传」完成；分片上传取消；资产详情封面加载、浏览器里真实播放（`currentTime > 1s`）、按租户模板转码成功；任务列表、按同一模板重转、取消、重试后成功；Webhook 登记、签名密钥只显示一次、刷新后不再出现、停用 / 启用、投递记录；用量历史天数；模板页同时有系统和租户模板；轮换后旧 Key 出现失效引导、页头变红、说明管理登录仍有效、管理页仍可用，粘贴新 Key 恢复；撤销有确认框、撤销后失效；管理 JWT 被拒绝后回登录页并说明「管理登录已过期」、租户 Key 保留、重新登录回原页；服务端直接拒绝被撤销的 Key 和伪造的 JWT；375 宽无横向溢出、抽屉打开后 Esc 关闭；「短剧场」指向旧播放页；退出；全程无页面脚本错误。旧播放页 `nginx/html/index.html` 自 Phase 4（`155f647`）以来没有改动。

验收截图在 [docs/console-redesign/acceptance/](docs/console-redesign/acceptance/)：11 个页面 / 状态 × 1440、1280、375 三种宽度，共 33 张，来自真实后端，数据是脚本建的测试租户。新 Key 明文和 Webhook 签名密钥所在区域已打码（品红色块）；页面上能看到的 `vk_…` 只是前缀，不是密钥，这些 Key 在验收结束前已被轮换和撤销。

还没看过的：Safari、Firefox、真实手机；读屏和对比度。

### CI：新增 Compose live 工作流

`.github/workflows/compose-live.yml`：push 到 `main`、`plan/**`、`fix/**`，向 `main` 提 PR，以及手动触发时起整套栈，依次跑 `e2e.py`、`playback_live.py`、`platform_live.py`、`media_live.py`、`core_live.py`、`drama_vod_live.py`、`browser_check.py`、`console_live.py`、`test_fencing_mysql.py`，任何一个非零退出都算失败，每个脚本的结论写进 Step Summary；每天定时跑一次并加跑 `fault_inject.py`（手动触发时可勾选）。密钥是运行时生成的随机值，不用 GitHub secrets，不发布、不部署。原来的 `ci.yml` 不变。

### 怎么在本机重跑

```bash
# 在仓库根目录；.env 写好口令和 VOD_ADMIN_USERNAME / VOD_ADMIN_PASSWORD（不要提交）
export COMPOSE_PROJECT_NAME=drama-vod-live
export COMPOSE_FILE=docker-compose.yml:docker-compose.platform-live.yml:docker-compose.media-live.yml
docker compose up -d --build
python3 scripts/e2e.py && python3 scripts/playback_live.py && python3 scripts/platform_live.py && python3 scripts/media_live.py
# 路线 B 起：COMPOSE_FILE 末尾再加 :docker-compose.lifecycle-live.yml，然后 python3 scripts/lifecycle_live.py
python3 scripts/fault_inject.py
python3 scripts/console_live.py --shots /tmp/console-shots     # 需要 playwright；或 CHROME_BIN 指向本机 Chrome
# 早期在 Compose 网络里跑的脚本
set -a; . ./.env; set +a
docker run --rm --network host --add-host nginx:127.0.0.1 --add-host mysql:127.0.0.1 \
  -e MYSQL_HOST=mysql -e MYSQL_USER=vod -e MYSQL_PASSWORD="$MYSQL_PASSWORD" -e MYSQL_DB=vod \
  -v "$PWD/scripts:/scripts:ro" drama-vod-live-worker python /scripts/core_live.py
```

### 仍然存在的边界

- 早期的 `core_live.py`、`drama_vod_live.py`、`kill_recover_submit.py` 仍按「在 Compose 网络内运行」编写，要用上面的 host 网络容器跑。
- 新工作流在合并前只在分支推送时跑；GitHub 托管 runner 上的耗时和稳定性以实际运行为准。
- Worker 启动时如果 vod-api 还没跑完 Flyway，补偿扫描会记一条 `sweeper_core_skip`（表不存在），下一轮自动恢复，不影响结果。

## 分支方案：管理控制台界面改版（plan/console-redesign）

本分支：`plan/console-redesign`，基线 `main` = `7bc59d3`（`7bc59d34812507c2fad6230f52f59f7525eda45d`，fix/leftovers 已合并）。下面是产品需求，实现完成后把实现说明写在本节后面，不要改需求原文。

本轮只写需求文档，不在此时启动设计或开发。界面视觉方案由 Fable 出，Frank 拍板后再由 Frank 自行驱动 Claude 在本分支实现。不要自行合并到 `main`，不要另开平行分支，不要 force-push，不要把密钥写进仓库或截图。决策记录见 [docs/decisions.md](docs/decisions.md)。

> 当前状态（2026-11-15）：需求原文和设计预览已落盘，Frank 已确认风格（见后文「设计预览」节末和 [docs/decisions.md](docs/decisions.md) 同日第二条）。下一步是把已批准样式接入真实 Vue 控制台，实现需求见「实现需求」一节。仍不要自行合并或另开平行分支。

### 目标

把现有「视频云控制台」整理成好看、好用的标准后台：左侧一列导航用来切页，右侧展示当前页内容。先改布局、层级和提示，让人能看懂自己在哪、用的是哪一种身份，现有管理功能和租户功能都要还能走通。

这轮给谁用：检查和演示时用的管理员，以及选用租户 API Key 后操作资产、任务、Webhook、用量的人。

### 现状

当前控制台已经能完成管理登录、租户与 Key、模板、资产（含分片续传）、任务（取消 / 重试 / 按模板重转）、Webhook、用量与配额，以及资产详情试播。问题在界面和引导，不在缺这些功能。

- 所有入口挤在顶部一行文字链接（管理端：租户与 Key、模板；租户端：资产、任务、Webhook、用量与配额；另有通往旧「短剧场」播放页的入口）。没有固定的左侧导航，右侧也没有稳定的页头区来放标题和当前身份。
- 深色背景加金色按钮，字体、间距、表单、表格和按钮没有统一、耐看的规范，宽屏内容被收在较窄的中间栏里，整体看起来简陋。
- 管理登录（JWT）和租户 API Key 是两套权限，页面没有把这层区别说清楚。管理员登录后还不能直接操作资产等租户页；需要先有租户，再创建或选用一把 Key，并「在本标签页使用」。
- 没选 Key、Key 无效或过期时，租户页容易直接打出 `unauthorized（401）` 这类接口原文，而不是告诉人「还没选 Key / Key 已失效，去创建或换一把」。登录页虽然有一句说明，但进到资产等页面后，引导不够，检查时已经踩过。

### 要做的

1. **标准后台布局**
   登录之后的所有后台页，统一为左侧固定导航 + 右侧内容，不要再把全部入口挤在顶上一排文字链接。
   - 左侧导航按分组排列，并标明当前所在页。管理端：租户与 Key、模板。租户端：资产、任务、Webhook、用量与配额。详情页（租户详情、资产详情、任务详情）归在对应分组下，导航高亮其所属列表页。
   - 右侧上方放页面标题，以及当前管理员身份和已选用的租户上下文（用备注、租户名或 Key 前缀等可辨认、但不暴露秘密的信息；**不要在顶栏、侧栏或普通列表里展示 Key 明文**）。下方是该页的表格、表单或详情。
   - 登录页单独处理，未登录时不要套用这套后台壳，登录后再进入。
   - 「短剧场」仍指向旧播放页。改版后要保留可达入口，但不要改那个播放页本身；入口放在侧栏的什么位置属于视觉细节，尚未拍板。（需求落盘时尚未拍板；同日已确认放在侧栏底部，见后文。）

2. **现有功能全部保留**
   只改界面、布局和提示，不增新业务，也不丢掉现在能做的事：
   - 管理端：登录与退出；租户的创建、启用/停用、配额（存储、并发、每日转码）；API Key 的新建、轮换、撤销，明文只在创建或轮换后显示一次，可「在本标签页使用」；模板的查看，以及在租户详情里创建租户模板。
   - 租户端：选用或更换本标签页的租户 Key；资产列表与上传（小文件一次传完，大文件分片、失败后可续传、可取消）；资产详情与封面展示、对 READY 资产申请播放 token 并试播；按模板发起转码或重新转码；任务列表与详情，取消、重试、按同一模板重新转码；Webhook 登记、启停、签名密钥只显示一次、投递记录；用量与配额（含近期按天历史）。
   - 管理接口继续只用管理登录身份，租户接口继续只用已选用的租户 Key。不要让管理登录代替 Key 去调租户接口，也不要在页面上绕过服务端鉴权。

3. **权限和失败要说人话，不要只甩 401**
   - 未登录就进入需要管理员身份的页面：引导去登录，而不是先画出一个以报错为主的页面。
   - 已登录但还没选用租户 Key，就进入资产、任务、Webhook、用量等租户页：给出明确说明和入口（去创建租户 / 新建 Key / 选用 Key），不要把 `unauthorized（401）` 当作主要提示。
   - 已选用的 Key 无效、过期、被撤销，或管理登录已过期：说清是哪一种身份失效，并给到重新登录或更换 Key 的入口。服务端拒绝时，页面可以说明「没有权限 / 已失效」，但不要把接口状态码当成唯一正文。
   - 空列表、加载中、操作失败（含并发冲突、配额用尽等已有错误）用同一套状态展示，失败原因要能读懂。
   - 这些都是界面引导。真正的权限判断仍以服务端为准，前端不得伪造已登录或已授权的假象。

4. **视觉和排版一致，空间用好**
   字体层级、间距、表单、表格、按钮，以及加载 / 空 / 错误状态要有统一规范。宽屏（见下方桌面视口）把左侧导航和右侧内容排开，表格和表单不要挤在过窄的中栏里，导致大片留白或互相重叠；窄屏至少能用：导航可到达各页，主要内容可滚动查看，必要操作仍能完成，不要出现挡住内容又无法关闭的层。

   配色、字体家族、是否使用现成 UI 组件库等尚未选定，见「尚未拍板」。不要把未批准的视觉方案写进实现说明当成既定设计。（需求落盘时尚未选定；同日已确认，见后文。实现时以已批准的设计稿为基准。）

### 本分支不要做

- 不改后端接口、鉴权方式和数据模型，不改接口的成功/失败结果。
- 不改旧的短剧播放页（「短剧场」）。
- 不做 OAuth、完整 RBAC、第三方登录或多管理角色，不把管理身份做成租户 Key 的替代。
- 不借这次改版加新业务（例如新的媒体能力、新的管理模块、短剧生成、推荐、直播）。
- 不在需求或实现说明里写入密钥、口令、完整 Key 明文；验收截图同样不能出现这些内容。
- 本文件只是需求。不要在没有 Frank 明确指令时启动 Fable 或 Claude，不要擅自选定配色方案或 UI 组件库，也不要在需求里写实现步骤、模块划分或接口改法。

### 尚未拍板（不要当成已定）

> 本节是需求落盘时的状态。2026-11-15 Frank 已确认 Fable 设计稿，实现时以已确认的设计为基准，不再把下面五项当成未定。确认结果见「设计预览」节末和 [docs/decisions.md](docs/decisions.md) 同日第二条。

Frank 这轮只定了侧栏结构（左导航、右内容，以及上面的导航分组）。下列事项交给 Fable 出方案，Frank 拍板前都还没定：

- 配色、字体家族、圆角和装饰风格
- 是否引入现成 UI 组件库、以及用哪一套
- 侧栏在窄屏上的具体收纳方式（只要满足「窄屏至少可用」）
- 「短剧场」入口在控制台里的具体位置
- 除侧栏分组以外的其他视觉细节

实现时不要把未拍板的选项写成已经批准。

### 怎么算整体做完

实现说明写在本节后面，对照下面逐条附上证据（真实操作结果、构建或测试输出、未跑的项单独列出）。不要编造测试数据或性能数字。「代码已写」不算做完。

1. **桌面布局**：在 **1440×900** 视口下（这是验收用的参考尺寸，不是已定的设计稿尺寸），登录后任意后台页都是左侧导航 + 右侧内容；侧栏、页头、主内容不重叠，也不互相挡住必要信息。导航分组正确，当前页（含详情页对应的列表项）高亮正确。右侧能看到页面标题，以及当前管理员、已选租户上下文；上下文中不可出现 Key 明文。将视口收到 **1280** 宽时，布局仍然可用、无重叠。再收到 **375** 宽时，各页仍可导航到达，主要内容可滚动，主要操作可完成。
2. **路由可达**：现有后台页都能从导航或对应列表进入，并正确高亮：登录、选用 Key、租户列表与详情、模板、资产列表与详情、任务列表与详情、Webhook、用量与配额。未登录访问管理页会去登录；未选 Key 访问租户页会去明确的选用/创建引导，而不是先进入一个以 `unauthorized（401）` 正文为主的页面。
3. **权限引导**：分别走通并记录——未登录打开「租户与 Key」；已登录但未选 Key 打开「资产」；选用一把无效或已撤销的 Key 后打开「资产」；管理登录过期后打开管理页。每次都应出现可读的说明和正确入口，而不是把 `unauthorized（401）` 当作主要提示。用无效身份直接请求对应接口时，服务端仍拒绝（页面引导不能变成真正越权）。
4. **原有流程对照**：对照本需求列出的现有能力走通这些路径，结果与改版前一致：管理登录与退出；创建租户、改配额、停用/启用；新建 Key（明文只出现一次）并「在本标签页使用」；轮换、撤销 Key；上传小文件、大文件分片续传与取消；资产列表/详情/封面/试播；按模板转码或重转；任务取消、重试、按同一模板重转；Webhook 登记（密钥只出现一次）、启停、查看投递记录；查看用量与配额。旧「短剧场」播放页内容与改版前一致。不要为了验收编造并不存在的测试数据。
5. **状态展示**：至少各举一例——空列表、加载中、操作失败（使用现有能力里已经会出现的失败，例如并发冲突或配额用尽，不要编造数据）。展示方式同属一套规范，文案能看懂原因。
6. **秘密不进画面**：用于验收的截图和文档里，不得出现管理口令、JWT、API Key 明文或 Webhook 签名密钥。
7. **构建与测试**：实现后附上这次真实跑过的控制台构建结果，以及仓库里现有、与本次改动相关的检查结果。没跑的项写明名称和原因，不要写成已经通过。

## 管理控制台改版：设计预览（`plan/console-redesign`，风格已于 2026-11-15 由 Frank 确认；当时尚未接入 Vue，未合并）

这一节是 Fable 按上面需求出的**视觉方案和可预览设计稿**，不是实现说明。提交当时的目的是先让 Frank 确认整体风格；文中「待确认」「建议」是提交时的措辞。2026-11-15 Frank 回复「风格可以，按这个做」，已批准该稿为实现基准（含不额外扩展深色模式），详见 [docs/decisions.md](docs/decisions.md) 同日第二条。设计稿本身仍是静态预览，当时确实还没有接入真实控制台。上面的需求原文没有改动。

### 怎么预览

设计稿是纯静态页面，在 `docs/console-redesign/`，不需要 Docker、后端或 `npm install`。

- 方式一：用浏览器直接打开 `docs/console-redesign/index.html`（在文件管理器里双击即可）。
- 方式二：起一个本地静态服务，可在手机或另一台电脑上看：

  ```bash
  cd docs/console-redesign && python3 -m http.server 8765
  # 然后访问 http://localhost:8765/
  ```

页面说明：

- `index.html`：总览，列出三个页面的全部示例状态，以及配色、字体、按钮、表单、表格、状态的规范节选。
- `login.html`、`tenants.html`、`assets.html`：三个核心页面。
- 每页顶部有一条深色「预览条」，用来切换示例状态。**预览条不属于产品界面**；地址后加 `?bare=1` 可隐藏它，窄屏下加 `&nav=1` 可默认展开侧栏抽屉。
- 不方便打开页面时，可以先看 `docs/console-redesign/screenshots/` 里的截图（均为示例数据）：

| 页面 | 截图 |
| --- | --- |
| 登录页（1440） | [login-1440.png](docs/console-redesign/screenshots/login-1440.png) |
| 租户列表（1440） | [tenants-list-1440.png](docs/console-redesign/screenshots/tenants-list-1440.png) |
| 新 Key 明文只显示一次（1440） | [tenants-new-key-1440.png](docs/console-redesign/screenshots/tenants-new-key-1440.png) |
| 资产列表（1440） | [assets-1440.png](docs/console-redesign/screenshots/assets-1440.png) |
| 资产：未选用 Key 的引导（1440） | [assets-no-key-1440.png](docs/console-redesign/screenshots/assets-no-key-1440.png) |
| 资产：Key 已失效（1280） | [assets-key-invalid-1280.png](docs/console-redesign/screenshots/assets-key-invalid-1280.png) |
| 资产：操作失败，配额用尽（1440） | [assets-quota-1440.png](docs/console-redesign/screenshots/assets-quota-1440.png) |
| 资产列表与侧栏抽屉（375） | [assets-375.png](docs/console-redesign/screenshots/assets-375.png)、[assets-nav-open-375.png](docs/console-redesign/screenshots/assets-nav-open-375.png) |
| 登录页（375） | [login-375.png](docs/console-redesign/screenshots/login-375.png) |

### 设计说明

以下是设计稿提交时的说明。其中标为「建议」的项已于 2026-11-15 由 Frank 确认，实现时按该稿执行，不再当作未定建议。

**布局**

- 登录后统一为后台外壳：左侧固定导航（宽 232），右侧上方是页头，下方是内容区，内容区独立滚动。登录页不套外壳。
- 侧栏按已定分组排列：「管理端」（租户与 Key、模板）和「租户端」（资产、任务、Webhook、用量与配额）。当前页用浅色底加左侧色条高亮；详情页高亮其所属的列表项。
- 页头左边是分组面包屑和页面标题，右边固定放两枚「身份胶囊」：管理员、租户 Key。租户 Key 只显示备注和前缀，不显示明文。
- 内容区最大宽度 1360，表格和表单铺满，不再收在窄中栏里。
- 建议「短剧场」入口放在侧栏最底部，标注「旧播放页」并带外链图标，和后台页面分开。
- 建议窄屏（宽度不超过 860）把侧栏收成左侧抽屉：页头出现菜单按钮；点遮罩、抽屉里的关闭按钮或按 Esc 都能关。身份胶囊换到标题下一行，表格在卡片内横向滚动。

**两种身份怎么让人看懂**

- 全站固定用两种颜色加文字区分：靛蓝 = 管理登录，青绿 = 租户 Key。侧栏分组标题旁写明「用管理登录」「用租户 Key」；页头两枚胶囊同色。
- 登录页左半边直接解释两种身份各管哪些页面，以及首次使用的顺序：管理登录 → 建租户、新建 Key → 在本标签页使用。
- 没选 Key 时：侧栏「租户端」各项带锁形标记但仍可点；页头胶囊变成虚线的「未选用租户 Key · 去选用」；进入租户页看到的是整页引导（三步进度、去「租户与 Key」新建 Key、直接粘贴已有 Key），不出现报错。
- Key 失效时：页头胶囊变红并写「租户 Key 已失效」；内容区说明是租户 Key 被拒绝、常见原因、管理登录仍然有效，并给「换一把」「粘贴另一把」「重试」。前端只能知道服务端拒绝了这把 Key，分不出是撤销、过期还是租户停用，所以文案把这几种原因并列写出，不假装知道是哪一种。
- 管理登录过期时：回到登录页，表单上方提示「管理登录已过期」，并说明已选用的租户 Key 不受影响。
- 接口原文（如 `unauthorized（401）`、`429 quota_exceeded`）保留，但降为提示里最后一行小字，供排查用。

**统一规范**（全部写在 `docs/console-redesign/console.css` 顶部的变量和各段样式里）

- 配色建议：浅色后台。主色靛蓝 `#3451D1`（同时代表管理登录），租户 Key 青绿 `#0B7A75`，页面背景 `#F4F6F9`，卡片和侧栏白色，正文 `#1B2430`，成功 / 提醒 / 危险分别为 `#1C7C4F` / `#9A5B00` / `#C0352D`。不沿用原来的黑金色。
- 字体建议：系统字体（苹方 / 微软雅黑 / Segoe UI），不加载外部字体；编号、前缀、接口原值用等宽字。正文 14，说明 13，卡片标题 14，区块标题 16，页面标题 20。
- 间距以 4 为基数；控件高 36（表格内小按钮 28）；圆角卡片 8、控件 6。
- 按钮分主要、次要、文字、危险四种，每个区块最多一个主要按钮；处理中显示转圈并禁用。
- 表格：表头浅灰底、行高 52、数字右对齐、操作列靠右；状态用「颜色 + 中文」徽标，旁边用小号等宽字保留接口原值（如 `READY`）。
- 加载 / 空 / 错误：加载时保留表头并用骨架条占位；空状态是图标、一句现状、一句下一步；错误在页面上方用提示条说明「哪里没成功、原因、下一步」，并给重试。三者出现在卡片内同一位置。
- 组件库建议：不引入现成 UI 组件库。现有控制台只有 `vue` 和 `vue-router` 两个依赖、十余个页面，用这一份样式规范加少量公共组件就够。这一条同样待 Frank 确认。（提交时待确认，同日已确认：不引入大型或现成 UI 组件库。）

### 哪些已完成，哪些是示例，哪些还没接入

**已完成（本次提交）**

- 一套样式规范 `console.css`，和共用的后台外壳（侧栏、页头、身份胶囊、窄屏抽屉）。
- 三个核心页面的设计稿及其状态：
  - 登录页：默认、登录中、口令错误、登录过期后跳回。
  - 租户与 Key：租户列表、租户详情里的 Key 管理与配额、新 Key 明文只显示一次并可「在本标签页使用」、加载中、空列表、加载失败。
  - 资产列表：正常、未选用 Key、Key 已失效、上传中、分片上传中断可续传、加载中、空列表、操作失败（配额用尽）。
- 用无头 Chromium 在 1440×900、1280×800、375×812 下对上表列出的页面和状态截图并逐张看过：侧栏、页头、内容不重叠，375 宽下抽屉能展开。截图即 `screenshots/` 里的文件。上表以外的状态（登录中、口令错误、登录过期、租户详情无新 Key、各页加载中 / 空列表、租户加载失败、上传中、中断可续传）没有截图检查。

**只是示例**

- 页面里的所有数据都是编的：租户名（示例租户 A–D）、ID、容量、次数、时间、资产编号、文件名、上传进度、Key 前缀（`vk_…`）、管理员名 `admin`。页面上相应位置标有「示例数据」。
- 「新 Key 明文」处是占位文字 `EXAMPLE-KEY-…`，不是任何真实密钥的格式；资产封面是占位色块。
- 错误提示里的原因文案是设计稿措辞；其中「服务端返回」一行参照了现有前端对错误的拼接方式（状态码、`code`、`Retry-After`），具体触发场景以真实接口为准。
- 顶部深色预览条和 `preview.js` 只为切换示例状态，不属于产品。

**尚未接入真实功能**（以下是设计预览提交时的状态；实现工作见下一节，当时尚未开始）

- 这套设计**没有写进 `console/` 下的 Vue 代码**，现有控制台的界面和行为与 `main` 完全一致；`nginx/html/vod-console` 构建产物也没有动。
- 设计稿不发任何请求，不读写 `sessionStorage`：登录、创建租户、新建 / 轮换 / 撤销 Key、上传、刷新、翻页等按钮点了都不会执行。页面之间只有少量为演示流程加的跳转链接。
- 其余页面本轮没有出设计稿，确认风格后沿用同一套规范：选用租户 Key、模板、任务列表与详情、Webhook、用量与配额、资产详情（含试播）、租户详情里的用量 / 模板 / Webhook 投递区块。侧栏里这些项在预览中点了不跳转。
- 需求里「怎么算整体做完」的 7 条验收都还没有开始：它们针对的是真实控制台的实现，本次没有做任何实现，也没有跑控制台构建或仓库测试（没有改到相关代码）。
- 没有做的检查：设计稿未在 Safari、Firefox 和真实手机上看过；抽屉的点击开合、Esc 关闭只写了逻辑，没有做交互测试；未做系统的对比度和读屏检查。

### 当时待确认的事项（已于 2026-11-15 全部确认）

Frank 回复「风格可以，按这个做」。各项结论如下，实现时按此执行：

1. 整体风格：接受浅色后台 + 靛蓝主色。本稿没有做深色模式，**不额外扩展深色**。
2. 接受用靛蓝 / 青绿两种颜色区分管理登录和租户 Key。
3. 接受字体用系统字体，不引入大型或现成 UI 组件库。
4. 接受窄屏侧栏收成抽屉；「短剧场」入口放侧栏底部。
5. 接受状态显示成中文徽标，并保留接口原值（如「可播放 READY」）。

## 管理控制台改版：实现需求（风格已确认，待接入真实 Vue 控制台）

本节是风格确认之后的实现需求。继续在本分支 `plan/console-redesign` 上做，不要另开平行分支，不要自行合并到 `main`，不要 force-push。实现完成后，把实现说明写在本节后面，不要改前面的需求原文和设计预览说明。决策记录见 [docs/decisions.md](docs/decisions.md)。已批准的视觉基准是 `docs/console-redesign/`（样式在 `console.css`，交互与文案以登录、租户与 Key、资产列表三页预览及其中的规范为准）。

技术方案、模块怎么拆、如何接到现有 `console/`，由实现者用 pstack 的 architect 流程给出**至少两套、对齐现有前端**的落地取舍，Frank 拍板后再写代码。本文不写技术方案。不要推翻已批准的视觉风格，不要做无意义重构。若方案涉及新增产品行为、新增依赖或改后端，先停下来让 Frank 定，不要自行扩大范围。

### 要做什么

把已批准的设计落到**真实的 Vue 控制台**（`console/`），让人打开现有的管理界面就能用新布局和新样式，而不是只留下三张静态稿。

实现前先盘点 `console/` 里现有的页面、路由和操作，列出改版后必须还在的能力，避免只做设计稿里画过的页面而丢掉现有功能。改版后所有后台页都走真实接口和现有鉴权，不要改成静态占位数据，也不要伪造已登录或已授权。

必须覆盖并保留的现有能力：

- 登录页独立，不套后台外壳；管理登录与退出；管理登录过期后回到登录页并说明原因。
- 选用 / 更换本标签页的租户 Key（现有的选用 Key 页要保留并按同一套规范改版，不能只做「去租户详情里选用」而丢掉直接粘贴已有 Key 的路径）。
- 租户的创建、列表、启用/停用、配额（存储、并发、每日转码）；租户详情。
- API Key 的新建、列表、轮换、撤销；明文只在创建或轮换后显示一次，可「在本标签页使用」。
- 模板列表；在租户详情里创建租户模板。租户详情里现有的用量、模板、Webhook 投递区块都要保留，并沿用同一套规范。
- 资产列表与上传（小文件一次传完，大文件分片、失败后可续传、可取消）；资产详情与封面、对 READY 资产申请播放 token 并试播；按模板发起转码或重新转码。
- 任务列表与详情：取消、重试、按同一模板重新转码。
- Webhook 登记、启停、签名密钥只显示一次、投递记录。
- 用量与配额（含近期按天历史）。
- 「短剧场」入口放在侧栏最底部，仍指向旧播放页，不改那个页面。

登录之后的后台页统一为已批准的外壳：左侧导航（分组与高亮按已定结构），右侧页头放标题和当前身份，下方是内容。管理员与租户 Key 继续分开：管理接口只用管理登录，租户接口只用已选用的租户 Key。页头和侧栏只显示不暴露秘密的上下文（用户名、租户备注、Key 前缀等），**不要展示 Key 明文、JWT 或口令**。未登录、未选 Key、Key 失效或登录过期时，用设计稿里那套人话引导和正确入口，不要把 `unauthorized（401）` 当作主要提示；服务端拒绝时，页面引导不能变成真正越权。空列表、加载中、操作失败（用现有能力里已经会出现的失败，例如并发冲突或配额用尽）按已确认的规范展示：中文说明为主，接口原值放在次要位置。

尚未单独出设计稿的页面（选用 Key、模板、任务列表与详情、Webhook、用量与配额、资产详情、租户详情里的其余区块）沿用同一套已确认规范铺开，不要另做一套视觉。

保留现有路由和访问方式，现有后台页都能从导航或对应列表进入。构建产物仍按仓库现有规则提交，避免演示栈还要现场构建。

### 为什么

现有控制台功能是齐的，但布局和引导不好用；设计预览已经按这个方向画出核心页和规范，Frank 已确认风格。现在缺的是把这套样式接到真实控制台，让检查和演示时用到的就是改版后的产品，而不是还在用旧的顶栏链接页。

### 怎么算做完

实现说明写在本节后面，对照下面逐条附上证据。不要编造测试数据或性能数字。「代码已写」不算做完。没跑的项写明名称和原因。

1. **真实控制台，而不是静态稿**：打开的是 `console/` 构建出来的管理界面，页面请求真实 API，列表、登录、上传、选用 Key 等结果来自服务端，而不是 `docs/console-redesign/` 里的示例数据。设计预览可以留着作对照，但不能代替产品。
2. **盘点无遗漏**：实现说明里列出盘点到的现有页面、路由和操作，并说明每一项在改版后从哪里进入、结果与改版前一致。上面「必须覆盖」的能力都要能走通，包括一次性 Key 明文、「在本标签页使用」、分片续传与取消、详情试播、任务取消 / 重试 / 按模板重转、Webhook 与投递、用量与配额。
3. **布局与身份**：在 **1440×900** 和 **1280** 宽下，登录后任意后台页都是左侧导航 + 右侧内容，不重叠、不挡住必要信息；导航分组和高亮正确（详情页高亮所属列表项）。**375** 宽下侧栏以已确认的抽屉方式可打开、可关闭（含遮罩、关闭按钮和 Esc），各页可到达，主要内容可滚动，主要操作可完成。页头能看出当前管理员和租户上下文，其中不可出现 Key 明文。登录页不套后台外壳，视觉与已批准预览一致（浅色靛蓝、身份用靛蓝/青绿区分、系统字体、中文徽标加接口原值）。
4. **权限引导**：在真实浏览器里分别走通并记录——未登录打开「租户与 Key」；已登录但未选 Key 打开「资产」（以及至少再测一个其他租户页）；选用一把无效或已撤销的 Key 后打开「资产」；管理登录过期后打开管理页。每次都是人话说明和正确入口，而不是把 `unauthorized（401）` 当作主要提示。用无效身份直接请求对应接口时，服务端仍拒绝。
5. **秘密与截图**：验收用的状态和响应式截图不得出现管理口令、JWT、API Key 明文或 Webhook 签名密钥。新 Key 或 Webhook 密钥如果必须演示「只显示一次」，截图里要打码或使用一次性测试值且不要把完整明文写进 README。
6. **构建与检查**：`console` 下类型检查与构建通过（含 `vue-tsc`）；构建产物按仓库原规则更新并提交。本分支 CI 跑过，结果写进实现说明（含 run 链接）。在实际浏览器里把第 2、4 条的关键流程走一遍，记录用的浏览器和视口。仓库里与本次改动相关的现有检查，跑过的附真实输出，没跑的单独列出。

### 本阶段不要做

- 不改后端接口、鉴权方式和数据模型，不改接口的成功/失败结果。
- 不改旧的短剧播放页（「短剧场」只保留入口）。
- 不做 OAuth、完整 RBAC、第三方登录或多管理角色，不把管理身份做成租户 Key 的替代。
- 不借这次改版加新业务，不重开视觉方向，不额外做深色模式，不引入大型或现成 UI 组件库。
- 不在需求或实现说明里写入密钥、口令、完整 Key 明文。
- 不要另开平行分支，不要合并到 `main`，不要 force-push。

## 管理控制台改版：实现说明（`plan/console-redesign`，未合并；真实后端验收待 Frank 在另一台电脑上做）

这一节对应上面的「实现需求」。代码已写完、构建通过、CI 结果见文末；**带真实后端的浏览器验收和验收截图这次没有做**，原因和清单见「没跑的检查」。按「代码已写不算做完」的标准，本节目前只能算「实现完成、待验收」。

### 方案选择

- 需求要求用 pstack 的 architect 出方案。实现所用的电脑上没有安装 pstack（技能目录、插件、命令行都查过），方案由实现者按同样的产出形式给出两套，Frank 选了方案 A。这一点如实记录，不写成「已用 pstack」。
- **方案 A（已采用）**：加一层后台外壳和少量公共部件，11 个页面只改模板和样式类，脚本里的接口调用基本原样保留。
- 方案 B（未采用）：在 A 之上再抽通用数据表格和页面骨架，并把每页的加载逻辑改写成统一的组合函数。回归面更大，对十来个页面偏重。
- 没有新增依赖（仍只有 `vue`、`vue-router`），没有改后端、接口、鉴权和数据模型，没有改旧播放页。

### 改了什么

| 位置 | 内容 |
| --- | --- |
| `console/src/styles/console.css` | 已批准的样式规范，来自 `docs/console-redesign/console.css`，去掉预览条和总览页专用部分，末尾补了铺到其余页面用的少量样式（键值表、数字卡片、封面、播放器等）。 |
| `console/src/App.vue` | 后台外壳：侧栏分组导航与高亮、页头标题与两枚身份标识、窄屏抽屉、「短剧场（旧播放页）」入口。登录页不套外壳。 |
| `console/src/main.ts` | 路由路径不变；每条路由加了标题、分组、高亮项等信息。守卫见下文「行为变化」。 |
| `console/src/api.ts` | 请求方式不变。新增 `explain()` 把失败说成中文并保留接口原文；新增状态值的中文对照；租户接口 401 时标记「Key 被拒绝」；管理接口 401 时回登录页。 |
| `console/src/session.ts` | 新增「当前 Key 被服务端拒绝」这个界面状态（不持久化），和按 `expires_at` 判断管理登录是否过期的函数。 |
| `console/src/useAction.ts` | 多返回接口原文 `raw` 和「首次加载是否结束」`loaded`。 |
| `console/src/components/` | 新增图标、状态徽标、列表占位（加载 / 空 / 失败）、一次性密钥框、Key 引导；提示条改为「中文说明 + 接口原文小字」。 |
| `console/src/views/*.vue` | 11 个页面按规范改模板。`upload.ts` 没有改。 |
| `nginx/html/vod-console/` | 重新构建的产物。 |

### 盘点对照：现有页面、路由和操作

路由路径全部保留。下表列出盘点到的每一项在改版后的入口。

| 路由 | 页面 | 身份 | 操作 | 改版后从哪里进 |
| --- | --- | --- | --- | --- |
| `/` | 重定向 | — | 有管理登录去租户；否则有 Key 去资产；都没有去登录 | 不变 |
| `/login` | 管理登录 | 无 | 登录，成功后回到原页面或租户页 | 独立页面；页头「去登录」、各处引导 |
| `/key` | 选用租户 Key | 无 | 粘贴 Key 和备注并选用；显示当前已选用的备注 | 页头「去选用 / 换 Key」、引导页「直接粘贴」、登录页底部链接 |
| `/tenants` | 租户列表 | 管理 | 创建租户、启用 / 停用、进入详情 | 侧栏「租户与 Key」 |
| `/tenants/:id` | 租户详情 | 管理 | 改配额；新建 Key（名称、有效期、三个权限范围）；轮换；撤销（带确认）；一次性明文与「在本标签页使用」；近 7 天用量；模板列表与新建租户模板；最近 20 条 Webhook 投递 | 租户列表里的名称或「管理 Key」；侧栏高亮「租户与 Key」 |
| `/templates` | 模板 | 管理或租户 | 查看、刷新 | 侧栏「模板」 |
| `/assets` | 资产列表 | 租户 | 上传（小文件直传、大文件分片）、加密开关、续传、取消、刷新、下一页 | 侧栏「资产」 |
| `/assets/:id` | 资产详情 | 租户 | 状态与封面、申请播放 token 并试播、按模板转码或重转、该资产的任务 | 资产列表、任务列表里的资产号；侧栏高亮「资产」 |
| `/jobs` | 任务列表 | 租户 | 刷新，跳任务详情和资产详情 | 侧栏「任务」 |
| `/jobs/:id` | 任务详情 | 租户 | 取消、重试、按同一模板重转、刷新、该任务的 Webhook 投递 | 任务列表、资产详情、投递记录里的任务号；侧栏高亮「任务」 |
| `/webhooks` | Webhook | 租户 | 登记（地址和 4 种事件）、一次性签名密钥、启停、最近 50 条投递 | 侧栏「Webhook」 |
| `/usage` | 用量与配额 | 租户 | 当前用量与配额、历史天数可调、刷新 | 侧栏「用量与配额」 |
| 顶栏 | — | — | 退出、换 Key、去登录、去选用 Key、短剧场 | 页头两枚身份标识；短剧场在侧栏底部 |

每个操作调用的接口、请求体和幂等键与改版前相同（页面脚本基本未动）。**「结果与改版前一致」只在代码层面核对过，没有连真实后端逐项走通**，这部分待验收。

### 行为变化（都只在前端，已向 Frank 确认）

1. **管理登录过期立即回登录页**：以前管理接口返回 401 只清掉登录状态，人留在原页面看报错。现在会带着「管理登录已过期」的说明回登录页，登录后回到原页面；已选用的租户 Key 不受影响。本地 `expires_at` 已过时，进入管理页前也会同样处理。
2. **没选 Key 时在原页面显示引导**：以前访问租户页会被直接带去 `/key`。现在停在原页面，内容区换成引导（三步进度、去「租户与 Key」新建、直接粘贴已有 Key）。`/key` 页保留。
3. **Key 失效时保留并标记**：租户接口返回 401 时，这把 Key 留在本标签页并标记为失效，页头标识变红，内容区换成「Key 已失效」引导，可重试、换一把或粘贴另一把。前端分不出是撤销、过期还是租户停用，文案把这几种原因并列写出。
4. **「换 Key」不再先清掉当前 Key**：以前点「换 Key」会立即忘掉当前 Key 再去 `/key`。现在只是去 `/key`，页面上另有「在本标签页停用这把 Key」按钮。
5. **模板页两种身份都没有时显示引导**，以前只显示一句文字。身份规则不变：有管理登录看全部，否则用租户 Key 看本租户可用的。
6. **上传进度单独显示**在上传卡片里，不再和操作结果共用一条提示。
7. **错误提示**改为中文说明在前、接口原文（状态码、`code`、`Retry-After`）在最后一行小字。中文说明只按状态码和 `code` 归类。
8. 撤销 Key 仍用浏览器原生确认框。未登录但有 Key 的人仍可只用 Key 进租户页，此时页头显示「未登录管理端」，管理端导航带锁。

这些都是界面引导：管理接口仍只带管理 JWT，租户接口仍只带租户 Key，能不能调通始终由服务端判断。页头和侧栏只显示管理员用户名、租户备注和 Key 前缀，不显示 Key 明文、JWT 或口令。

### 已跑的检查

- **类型检查与构建**：在 `console/` 下运行 `npm run build`（即 `vue-tsc --noEmit && vite build`），通过。本机 Node 24 损坏，用的是 Node 22.23.4；CI 用 Node 24。输出：

  ```
  vite v6.4.5 building for production...
  ✓ 64 modules transformed.
  ../nginx/html/vod-console/index.html                   0.43 kB │ gzip:  0.31 kB
  ../nginx/html/vod-console/assets/index-B7WvsiWj.css   18.22 kB │ gzip:  4.29 kB
  ../nginx/html/vod-console/assets/index-DOP62ZW0.js   174.10 kB │ gzip: 61.58 kB
  ✓ built in 446ms
  ```

- **构建产物**已按仓库原规则提交到 `nginx/html/vod-console/`。
- **界面自查（不是验收）**：实现所用的电脑没有 Docker，起不了真实后端。为了不盲交，实现者在仓库外的临时目录起了一个一次性的模拟接口，用无头 Chromium 打开**真实构建产物**检查布局和引导逻辑。模拟接口和截图都没有进仓库，下面的结果不能当作第 1、2、4 条验收证据：
  - 逐页截图看过 1440×900、1280 宽和 375 宽下的登录、选用 Key、租户列表与详情、模板、资产列表与详情、任务列表与详情、Webhook、用量与配额，以及未选 Key、Key 被拒绝、登录过期几种状态。
  - 脚本交互 28 项全部通过：未登录打开「租户与 Key」去登录页；口令错误提示；登录后回到原页面并高亮；未选 Key 打开「资产」和「用量与配额」出现引导且正文不含 401；新建 Key 后明文框出现，点「在本标签页使用」后页头只有备注和前缀、整页不含明文；被拒绝的 Key 出现失效引导且管理页仍可用；粘贴可用 Key 后进入资产列表；429 `quota_exceeded` 和 409 的中文说明；管理 JWT 被拒绝后回登录页并说明、Key 仍保留；退出；375 宽下抽屉用菜单按钮打开，Esc、遮罩、关闭按钮、切页都能关，且无横向溢出；1280 和 1440 下侧栏与内容不重叠；全程无页面脚本错误。

### 没跑的检查

- **真实后端下的浏览器验收（验收第 1、2、4 条）**：没跑。原因是实现所用的电脑没有 Docker；Frank 表示验收在另一台电脑上做。待走的流程：登录与退出；创建租户、改配额、停用 / 启用；新建 Key 并「在本标签页使用」；轮换、撤销；小文件上传、大文件分片续传与取消；资产详情、封面、试播；按模板转码 / 重转；任务取消、重试、按同一模板重转；Webhook 登记、启停、投递记录；用量与配额；四种权限引导；用无效身份直接请求接口时服务端仍拒绝；旧「短剧场」页面内容不变。
- **1440 / 1280 / 375 验收截图（第 3、5 条）**：没有提交。自查截图来自模拟接口，放进文档容易被当成真实证据，所以没放。验收时截图请对新 Key 明文和 Webhook 签名密钥打码。
- **试播**：自查环境没有可播放的 HLS，播放器只看过未展开的状态。
- **真实文件上传**（直传、分片、续传、取消）：`upload.ts` 没有改，页面只改了展示，但没有实际传过文件。
- **Safari、Firefox、真实手机**：没看过，只用过无头 Chromium。
- **读屏和对比度**：没做系统检查。
- 仓库里的后端、Worker、Python 测试：本次没有改到相关代码，本地没有跑；CI 会跑。

### CI

提交 `d519bfa`（界面实现与构建产物）推送后的 CI 运行：<https://github.com/TinyPandar/drama-vod/actions/runs/43806925163>，结论 success。

| 任务 | 结论 |
| --- | --- |
| `console`（`npm ci`、`npm run build`、提交的构建产物与源码一致） | success |
| `vod-api` | success |
| `python` | success |

`console` 任务在 Node 24 下重新构建，产物与本地用 Node 22 构建后提交的一致。补充本小节的这次提交只改 README，其 CI 结果以 GitHub 上该提交的运行为准，这里不预先写结论。

## 分支方案：遗留 bug 清理（fix/leftovers）

本分支：`fix/leftovers`，基线 `main` = `ce86b9a`（Phase 6 和 CI 都已合并）。下面是需求，实现完成后把实现说明写在本节后面，不要改需求原文。

协作方式：Claude Code 在**本分支**上按清单做最小改动，不要合并到 `main`，不要另开平行分支，不要 force-push。做完由「短剧视频服务器开发bot」验收，Frank 说合再合。

技术怎么改由你定，每条的「验收」是判断做完的标准。不改的东西见最后。

### 要修的

1. **重试接口绕过「同资产有进行中任务就 409」**
   现状：Phase 5 返工后 `TranscodeService.transcode` 会锁住同一资产的全部任务行，任意模板进行中就返回 409；但 `POST /api/v1/jobs/{id}/retry` 仍只看自己这一行，可能让同一资产同时有两个任务在转。
   验收：同一资产已有另一个任务处于进行中时，retry 返回 409，且不新建任务、不写 outbox；没有冲突时 retry 行为与现在一致（包括 Idempotency-Key 重放）。H2 和真 MySQL 各有用例覆盖，MySQL 用例需要验证并发下也只有一个进行中任务。

2. **缓存命中后 Range 请求返回 200 而不是 206**
   现状：Phase 4 Docker 验收时 `playback_live.py` 30 项里唯一失败。回源 `api/main.py` 的 `StreamingResponse` 没带 `Content-Length`，Nginx 的 range 过滤因此跳过；`nginx_local_check.py` 的假回源总带 `Content-Length`，没暴露问题。
   验收：回源响应带正确的 `Content-Length`（对象大小已知时）；`nginx_local_check.py` 增加一个「回源不带 Content-Length / 带 Content-Length」的对照或直接让假回源与真回源一致，并在缓存命中后断言 Range 返回 206、`Content-Range` 正确；现有 Python 测试全过。

3. **`fault_inject.py` 的 MinIO 场景默认参数下不稳定**
   现状：默认参数 5/6，MinIO 场景在下载或转码阶段就停 MinIO，S3 客户端重试把短停机吸收了；要 `--outage-seconds 120` 才 PASS。
   验收：默认参数下 MinIO 场景能稳定触发它想验证的路径（停机时机或默认时长调整都可以，README 写清选择和理由）；不改 Worker 的重试逻辑来迁就脚本。

4. **`.gitattributes` 固定换行**
   现状：仓库没有 `.gitattributes`；Frank 的 Windows 默认 `core.autocrlf=true`，`nginx/templates/` 下的 `*.envsh` 如果被转成 CRLF，容器里会执行失败。
   验收：新增 `.gitattributes`，至少让 `*.envsh`、`*.sh` 固定 LF；不对仓库已有文件做整体换行重写（`git diff --stat` 里不应出现大量无关文件）。

5. **CI 把 Phase 6 的 MySQL 用例纳入检查清单**
   验收：`.github/workflows/ci.yml` 里要求必须执行且不跳过的 MySQL 用例清单加上 `vod.job.Phase6MysqlTest`，以及第 1 条新增的 MySQL 用例类（如果是新类）；push 后本分支 CI 全绿。

6. **README 状态标注**
   验收：README 里 Phase 6、CI 两节标题中的「未合并」改成已合并（Phase 6 合并提交 `c7ce6c9`，CI 合并提交 `ce86b9a`），只改标注，不改原文内容。

### 本分支不要做

- 新功能、重构、换框架、改消息 v1、改 Flyway V1–V13 已有文件（需要表结构变更就加 V14，但这几条预计不需要）。
- Phase 6 遗留的 N2–N12、多码率以外的媒体能力、雪碧图等。
- 不改 Windows 或 `~/.claude/settings.json`，不把密钥写进仓库。

### 怎么算整体做完

- 上面 6 条的验收都满足。
- `mvn test`（H2）和连真 MySQL 的测试全过；`scripts/contract_check.py` 和 `scripts/test_*.py` 全过；本分支 GitHub Actions 三个 job 都绿。
- 在下面「实现说明」里写清：每条改了哪些文件、怎么验证的、测试的真实数字、没在 Docker 上跑的项目明确标出来。不要编造任何性能数字。

## 遗留 bug 清理实现说明（`fix/leftovers`，未合并）

按上面 6 条做的最小改动。没有新增 Flyway 迁移，没改消息 v1、V1–V13、Worker 的重试逻辑和 `api/common.py` / `worker/common.py`；没有加新功能。

1. **重试也按资产拦进行中任务**
   - 改动：`vod-api/src/main/java/vod/job/TranscodeService.java`（`retry`），`V1Controller.java` 只改了接口注释。
   - `retry` 先不加锁读出任务的租户和资产（建行后不变），校验租户、处理 Idempotency-Key 重放，然后和 `transcode` 一样按 id 顺序 `FOR UPDATE` 锁住这个资产的全部任务行。同一资产另有任务（任意模板）在 `PENDING`/`RETRYING`/`RUNNING` 就 409，消息里写明是哪个任务、哪个模板；这时不重置任务、不写 outbox、不记事件和用量，带 Idempotency-Key 的请求随事务回滚，不留可重放的结果。加锁顺序变成「租户 → Idempotency-Key → 任务」，和类注释里写的一致（原来是先锁任务行再占 key）。没有冲突时行为不变：只允许 `FAILED`（配置允许时含 `CANCELLED`），跨租户 404，重放返回 200 + 同一任务。
   - H2：`TranscodeApiTest.retryConflictsWhenAnotherJobOfSameAssetIsActive`。另一个模板的任务依次处于 `PENDING`/`RETRYING`/`RUNNING` 时，重试返回 409（带和不带 Idempotency-Key 都测）；任务行状态和 `claim_version` 不变，任务数、outbox、用量、key 表都不变。另一个任务结束后，同一个 key 正常重试（202，`claim_version` +1）；再次重放返回 200 + 同一任务，outbox 不增加。反方向（ladder 在跑时重试 mobile）也是 409，跨租户仍是 404。把这条用例放到基线代码上跑，结果是红的（`expected:<409> but was:<202>`）。
   - MySQL：加在 `Phase5MysqlTest.concurrentRetriesAndRetranscodeOnSameAssetLeaveOnlyOneActiveJob`，没有新建测试类。先按顺序测一遍：另一个任务 `RUNNING` 时重试返回 409，不改行，也不写 outbox 和 key。再并发跑 10 轮：每轮同时发「重试 ladder_v1」「重试 mobile」「用第三个模板 hd 重新转码」三个请求，打同一个资产。每轮都断言状态码排序后是 `[202, 409, 409]`、该资产进行中的任务恰好 1 个、outbox 恰好 1 条。
2. **回源带 Content-Length，缓存命中后 Range 返回 206**
   - 改动：`api/main.py` 的 `internal_origin`：S3 `get_object` 返回整数 `ContentLength` 时，`StreamingResponse` 带上 `Content-Length`；拿不到大小就不带，不编造。
   - `scripts/test_api_playback.py`：新增 1 个用例，验证大小已知时 `content-length` 等于对象大小、没有 `transfer-encoding`、正文完整；原有用例补一条断言：大小未知时不带这个头。新增用例放到基线 `main.py` 上跑会失败（`KeyError: 'content-length'`）。
   - `scripts/nginx_local_check.py`：假回源默认和真回源一致，带 `Content-Length`。路径里有 `/nolen/` 时模拟修复前的行为：不带 `Content-Length`，靠关连接结束正文。新增 4 项检查：MISS 时 `Content-Length` 原样透传；缓存命中后 `bytes=1000-1099` 和 `bytes=-48` 都返回 206，`Content-Range` 与正文逐字节正确；对照组的回源不带 `Content-Length`，缓存命中后同样的 Range 请求返回 200 全量、没有 `Content-Range`。对照组说明 206 依赖回源 `Content-Length`，假回源不会再掩盖这个问题。
   - 另外用真实 uvicorn 起了 `api/main.py`（S3 换成假的，跳过 startup），按 Nginx 回源的 HTTP/1.0 请求：大小已知时响应头里有 `content-length: 256000`；对照路径没有这个头，正文同为 256000 字节。这个检查是临时手跑的，没有提交脚本。
3. **`fault_inject.py` 的 MinIO 场景**
   - 改动：只改 `scripts/fault_inject.py`。
   - 选择：**停机时长跟着故障走**。任务进入 `RUNNING` 后停 MinIO，一直等到 Worker 日志里出现该任务的 `job_error`，或任务已经进入终态，再启动 MinIO。`--outage-seconds` 改为停机上限，默认值从 15 改为 300。判定条件不变：必须看到该任务的 `job_error`，最后 `READY`，或 `FAILED` 且带 `error_code`。
   - 理由：Worker 只在下载源文件、上传产物和远端校验时访问 MinIO，转码期间不访问。固定 15 秒的停机通常落在 FFmpeg 转码期间，上传前 MinIO 已经恢复；落在下载阶段时，又会被 S3 客户端重试吸收，所以结果取决于转码耗时。改成一直停到 Worker 报错后，最晚也会在上传阶段撞上故障，不再依赖时机。没有改 Worker 的重试逻辑。只延长固定时长（例如 120 秒）也能过，但在慢机器或长源文件上仍可能不够，所以没有采用。
   - 验证：`py_compile` 通过。另外用桩替换 compose、数据库和日志，在本机走了一遍控制流：停机后第 3 秒出现 `job_error`，脚本立刻启动 MinIO 并判 PASS。**没有在 Docker 上跑**（本机没有 Docker），真实栈上是否稳定 PASS 还没验证。
4. **`.gitattributes`**
   - 新增 `.gitattributes`：`* text=auto`，`*.envsh`、`*.sh` 设为 `text eol=lf`（仓库里目前只有 `nginx/entrypoint.d/05-vod-env.envsh` 这一个匹配文件，需求里写的 `nginx/templates/` 下实际没有 `.envsh`）。
   - 验证：`git ls-files --eol` 显示 196 个已跟踪文件在索引里全是 LF。`git add --renormalize .` 之后，除本分支改过的文件外没有任何文件变化，没有整体重写换行。用 `git -c core.autocrlf=true clone` 克隆提交后的分支：197 个文件里只有 `05-vod-env.envsh` 检出为 LF（`attr/text eol=lf`，文件里 0 个 `\r`），其余 196 个文本文件照常转成 CRLF（和没有 `.gitattributes` 时一样）。
5. **CI**：`.github/workflows/ci.yml` 的必跑 MySQL 用例清单加入 `vod.job.Phase6MysqlTest`。第 1 条的 MySQL 用例加在已有的 `Phase5MysqlTest` 里，不是新类，清单不需要再加。README「检查」一节的 CI 说明同步补上这个类名。
7. **额外：CI 的 python job 装 ffmpeg**（不在 6 条清单里，但不修的话「三个 job 都绿」做不到）
   - 现状：Phase 6 新增的 `scripts/test_poster.py` 里有一组用例（5 个）要用真实 ffmpeg 抽帧，没有 ffmpeg 时用 `skipUnless` 跳过。CI 是基于 Phase 6 之前的 `main` 写的，没装 ffmpeg；两个分支合并后，「出现 skipped 即失败」的检查让 python job 变红。`main` 上 `ce86b9a` 的 run [43583010001](https://github.com/TinyPandar/drama-vod/actions/runs/43583010001) 和本分支计划提交 `ad75d0f` 的 run [43583046519](https://github.com/TinyPandar/drama-vod/actions/runs/43583046519) 都是这样：`test_poster.py` 输出 `OK (skipped=5)`。
   - 改动：`.github/workflows/ci.yml` 的 python job 安装依赖时 `apt-get install ffmpeg`（CI 方案原文允许「实际需要就装」），注释同步更新。没有改测试，也没有放宽跳过检查。
6. **README 状态**：Phase 6、CI 两节实现说明的标题从「未合并」改成「已合并到 `main`」，分别注明 `c7ce6c9`、`ce86b9a`。用 `git merge-base --is-ancestor` 确认这两个提交都在 `origin/main` 上。正文没动。

**本分支实际跑过的测试**（2026-11-14，macOS arm64，本机**没有 Docker，也没有 MySQL**）

- `cd vod-api && mvn test`（Temurin 21.0.13 + Maven 3.9.9，H2）：**145 个用例，0 失败，13 个跳过**（跳过的是 MySQL 用例：`MysqlSemanticsTest` 5 个、`Phase5MysqlTest` 4 个、`Phase6MysqlTest` 4 个）。同一台机器上基线 `ad75d0f` 是 143 个用例、12 个跳过；本分支新增 H2 用例 1 个、MySQL 用例 1 个。
- 连真 MySQL 的 Java 用例本机没有跑，只在 GitHub Actions 的 `mysql:8.4` 服务容器里跑（结果见下面 CI 一条）。
- **GitHub Actions**：
  - 第一次推送（`6a57312`）的 run [43584509113](https://github.com/TinyPandar/drama-vod/actions/runs/43584509113)：`vod-api`、`console` 绿；`python` 红，原因是上面第 7 条说的 `test_poster.py` `skipped=5`，和本分支改动无关。
  - 装 ffmpeg 后（`cc364ab`）的 run [43584712437](https://github.com/TinyPandar/drama-vod/actions/runs/43584712437)：**三个 job 都绿**。
    - `vod-api`：29 个测试类，**145 个用例**，skipped=0、failures=0、errors=0。`MysqlSemanticsTest` 5 个、`Phase5MysqlTest` 4 个（含第 1 条新增的并发用例）、`Phase6MysqlTest` 4 个，都连 `mysql:8.4` 真跑。
    - `python`：13 个脚本全过，没有跳过。`test_fencing_mysql` 30、`test_poster` 16（ffmpeg 6.1.1，5 个真实抽帧用例这次真跑了）、`test_api_playback` 9。
    - `console`：构建通过，提交的产物没过期。
- Python 3.12.13（按 `api/requirements.txt`、`worker/requirements.txt` 和 `httpx==0.28.1` 新建的 venv）：`contract_check.py`、`test_api_playback`（9，原 8）、`test_bridge`、`test_dlq`（5）、`test_hls_verify`、`test_output_gc`（9）、`test_playback_contract`、`test_poster`（16）、`test_transcode_template`（3）、`test_vod_client`、`test_worker_fencing`（7）、`test_worker_reliability`（39）全过，没有跳过。`test_fencing_mysql.py` 本机连不上 MySQL（退出码 5），只在 CI 里跑。
- `nginx_local_check.py`（Homebrew nginx 1.31.4，不是 compose 的 `nginx:1.27-alpine`）：**46/46**（基线 42/42，新增 4 项）。
- **没跑**（需要 Docker）：`docker compose` 起栈、`scripts/playback_live.py`（缓存命中后 Range 206 在真实栈上是否通过）、`scripts/fault_inject.py --only minio`（默认参数下是否稳定 PASS）、`scripts/e2e.py`、`platform_live.py`、`media_live.py`。这些都没有验证结果。

## 分支方案：Phase 6 高级媒体能力（第一刀）

本分支：`plan/phase-6`，基线 `main` = `248b6e3`（Phase 5 已合并）。下面是本分支方案。实现完成后，把实现说明写在本节后面，不要改方案原文。

协作方式：其他 agent 请在**本分支**上按下面清单做最小改动开发；不要自行合并到 `main`，也不要另开平行分支。开发完后由「短剧视频服务器开发bot」检查，没问题再合并。

对照文档：[路线图](docs/panda-vod-roadmap.html) 第 06 节（`POST /uploads` 可返回预签名 URL **或 multipart 信息**）、第 05 节（MinIO 上还有缩略图这类对象）、第 09 节 Phase 6、第 11 节「对象存储直传：预签名上传、校验、Multipart 扩展」。目标：**大文件能断点续传，转码成功后资产有可展示的封面**。Phase 6 在路线图里是长期迭代，本分支只做第一刀，其余明确留到后续。

### 现状（不要推倒重来）

- 上传：`POST /api/v1/uploads` 只签发单次 `put_url`（`ObjectStorage.presignPut`），完整文件一次 PUT 到源桶；`POST /uploads/{id}/complete` 再 HEAD 校验大小、MIME、扩展名和配额，然后同一事务里建任务和 outbox。会话有 TTL（默认 900 秒），过期 complete / 重放会 409；没有 abort 接口，也没有清理未完成分片的后台任务。Nginx 上传网关 `client_max_body_size` 是 5g，小文件这条路径要继续能用。
- 存储接口：`ObjectStorage` 目前只有 `head` 和 `presignPut`。实现有 `S3ObjectStorage`（预签名用公网地址，HEAD 用内网地址）、测试用 `MemoryStorage`、未配置时的 `UnavailableStorage`。改接口时这三个都要跟上。
- 封面：资产已有 `duration_ms` / `width` / `height`（Worker 成功事务里用 ffprobe 结果写入），没有海报字段。控制台资产列表和详情也不展示封面。
- 播放路径：Core 产物在 `assets/v1/{asset_id}/{job_id}-{claim_version}/`，签名 URL 是 `/hls/v1/{sig}/{exp}/{asset}/{gen}/{file}`，`file` 已允许 `poster.jpg` 这种名字；缓存键带 gen，前缀一换就不会命中旧封面。Worker 上传后会按本地清单 list 远端（名字、大小、无多余对象），不一致就 `STORAGE_ERROR` 重试——**封面如果生成了，必须进这份清单**。
- 成功事务、`claim_version` 围栏、`hls_prefix` 原子切换、旧前缀 GC，都按 Phase 4 的规则走，本分支不要另做一套切换。

### 要做的事

1. **存储层补上 Multipart**
   在 `ObjectStorage` 增加：创建分片上传、给指定 part 签发预签名 URL、列出已上传分片、完成分片上传、取消分片上传。`S3ObjectStorage` 走 MinIO / S3 的标准 API（创建和完成走内网客户端，分片预签名走公网 presigner，和现在的 PUT 分工一致）。`MemoryStorage` 做成可测的内存实现（能完成、能取消、能列出 etag）；`UnavailableStorage` 继续抛未配置。不要引入第二家云厂商 SDK。

2. **上传会话支持 `mode=multipart`，单次 PUT 保持默认**
   `POST /api/v1/uploads` 增加可选字段 `mode`：缺省或 `put` 时响应与现在完全兼容（仍返回 `put_url`）。`mode=multipart` 时进入分片会话：在源桶创建 S3 multipart，把 `upload_id`（S3 的 id）和 mode 记到 `vod_upload_session`（Flyway **V13**，只追加列，不要改 V1–V12）。响应返回 `session_id`、`asset_id`、`part_size_bytes`、`expires_at`，**不要**返回 `put_url`。对象键规则仍是 `raw/t{tenant}/{asset}/source.{ext}`。Idempotency-Key 语义与现在相同：同一租户同一 key，OPEN 未过期就重放原会话（multipart 重放不要再 create 一次 S3 上传）；已 COMPLETED 不再签发上传 URL。`part_size_bytes` 做成配置项，默认不得小于 S3 要求的 5 MiB（最后一块可以更小）。会话 TTL 继续可配；multipart 文件可能较慢，允许在 OPEN 且未过期时通过下面的分片接口重新签发 URL，但不要在无上限地续期——如需延长，用配置项设一个上限，并在 README 写清。

3. **分片签发、完成、取消**
   - `POST /api/v1/uploads/{id}/parts`（或等价清晰的路径）：对仍 OPEN、未过期、且属于本租户的 multipart 会话，为请求里的 part 号列表签发预签名 URL。跨租户或会话不存在按现有规则 404；`put` 模式的会话来调返回 409；过期 409。
   - `GET /api/v1/uploads/{id}/parts`：列出存储里已经上传成功的 part 及 etag，供断点续传。客户端用这份列表跳过已传分片，再向 POST 要缺失 part 的 URL。
   - `POST /api/v1/uploads/{id}/complete`：`put` 模式保持现有行为（先 HEAD 再校验再建任务）。multipart 模式必须先用客户端上报的 `{part_number, etag}` 在存储侧 `completeMultipart`，成功后再走现有 HEAD + `UploadPolicy` + 配额 + 建任务/outbox 这条事务；S3 还没组合完就不要建任务。分片 etag 对不上、缺 part、或 completeMultipart 失败，返回 4xx，会话仍是 OPEN，允许重试 complete，不要把源对象留在半组合状态还去转码。Multipart 完成后的对象 ETag 一般不是整个文件的 MD5，SHA-256 也可能没有：校验继续以 HEAD 的 size、content-type、扩展名、配额为准，不要因为缺 checksum 就 422。
   - 新增 `POST /api/v1/uploads/{id}/abort`：本租户 OPEN 会话可取消；multipart 还要在存储侧 abort，避免 MinIO 留下未完成分片。已 COMPLETED 的会话返回 409，禁止取消以免动到已经在转码的源文件。取消后会话记 `ABORTED`。资产若仍是 `UPLOADING`，按现有上传生命周期的处理方式收尾（不要误删别的资产的源对象）。

4. **过期会话清理未完成的分片上传**
   增加一个后台清理（间隔可配）：找出已过期仍为 OPEN 的 multipart 会话，abort 存储侧上传，会话打成 `ABORTED`。只处理过期 OPEN，不要碰 COMPLETED，也不要删除已经 HEAD 校验过的源对象。`put` 模式过期会话如果源桶里留下了未 complete 的对象，本分支可以一并删未引用的源对象，但必须先确认会话未完成、资产仍是 `UPLOADING`、对象键与会话一致；吃不准就只做 multipart abort，把 `put` 残留写成风险。清理要可多实例跑（条件更新领取），失败只打日志并下次再试。

5. **转码成功时在同一产物前缀里写出封面**
   Core Worker 在 HLS 产物之外，用 FFmpeg 参数数组（不拼 shell）从源视频抽一帧 JPEG，上传到**当前任务专属前缀**下的固定名字，例如 `poster.jpg`。抽帧位置做成配置项（例如第 1 秒；比片长短则取 0 秒或中间一帧）。封面必须进入本地清单，并和 m3u8、ts 一起做远端 list 对账，过不了就按现有 `STORAGE_ERROR` 重试，不要单独提交一个没登记的对象。抽帧失败（无视频流、损坏帧等）**不要让整次转码失败**：任务仍可 READY，只是没有封面。旧 Demo 产物路径和 `content_key` 不要动。

6. **成功事务登记封面，切换和 GC 复用现有前缀**
   V13 给 `vod_media_asset` 增加可空的 `poster_key`（或等价字段）。Worker 成功事务里，在写 `hls_prefix` / `READY` / 前缀 `ACTIVE` 的**同一事务**里写入本期前缀下的封面键；没有封面就写 NULL。重新转码成功后，封面跟着新前缀走；旧封面留在旧前缀里，由现有 GC 删除，不要另写一套海报生命周期。不要把封面登记成会打乱 `uk_bucket_key` 或源对象角色的东西——HLS 分片本来也不逐条进 `vod_storage_object`，封面同样只挂在前缀和资产字段上即可。已 READY 的旧资产没有封面是正常的，需要重新转码才会生成。

7. **资产查询和播放接口返回签名封面地址**
   `GET /api/v1/assets`、`GET /api/v1/assets/{id}`，以及 `POST /api/v1/assets/{id}/playback-token`（和现有 `GET .../playback` 如仍返回播放信息），在有 `poster_key` 时返回 `poster_url`：用和该资产当前 `hls_prefix` 相同的签名规则签发，过期时间与播放 URL 同一套上限。没有封面时字段为 `null`，不要 500。跨租户仍然 404。`poster_url` 不要写进 Webhook 事件体（签名 URL 会过期）；若要通知，只允许在 `transcode.succeeded` 上**追加**可选字段比如 `has_poster`，不能改事件名，也不能带密钥或签名 URL。

8. **控制台：大文件走分片，列表和详情能看到封面**
   控制台上传：小文件可以继续单次 PUT（与默认 `mode=put` 兼容）；达到可配阈值（建议默认等于 `part_size_bytes`）时改走 multipart：按 part 上传、失败重试未完成的 part、最后 complete；提供取消。阈值和 part 大小不要写死在前端常量里，写进 open 接口的响应（例如 `part_size_bytes`），前端照着切。资产列表和详情用 `poster_url` 展示封面，没有则保持现在的占位。不要上新的大型 UI 库。构建产物仍提交到 `nginx/html/vod-console/`，规则与现在一致。

9. **契约、配置与指标**
   更新 `docs/phase-0-contract.md` 的 Core 上传与资产部分（旧 Demo 条目保持冻结原文）：写清 `mode`、分片接口、complete 对两种模式的差异、abort、过期清理、`poster_url` 与抽帧失败不阻断 READY。README 补接口、配置项（part 大小、会话 TTL 上限、抽帧位置、清理间隔）、以及若有新指标时的名字。指标用 Micrometer 即可，例如分片完成/取消、过期 abort 次数、抽帧成功/跳过；标签不要带 URL、租户或密钥。不要编造性能数字，默认值只是开发默认。

10. **验收脚本与测试**
    补 Java 测试：`put` 路径原有用例继续通过；multipart 覆盖创建、重放 Idempotency-Key、列出已传分片后续传、complete 后建任务、缺 part / etag 错误不建任务、abort 后存储侧取消、跨租户 404、put 会话调用分片接口 409、已 complete 禁止 abort。`MemoryStorage` 必须能支撑这些测试。有条件时用 MinIO（测试容器或已有栈）再跑一条真实分片上传。Worker 单测覆盖：封面进清单、抽帧失败仍能提交成功且 `poster_key` 为空、成功事务写入的 key 落在当期前缀。控制台构建后核对产物已提交。有 Docker 时用独立项目名（如 `drama-vod-p6`）起栈，跑现有 `e2e.py`、`platform_live.py`（旧能力回归），并新增或扩展脚本覆盖：multipart 上传后转码 READY、资产接口能拿到可访问的 `poster_url`、过期/取消后 MinIO 上没有残留的未完成分片。`playback_live.py` 因 Range 问题已知可能有一项失败，不要为了让它全过而在本分支改回源。

**可选小项（不做不影响验收，做了单独说明）**：雪碧图 + WebVTT 的进度条预览；给已 READY 的资产单独重抽封面而不重转全部码率；multipart 会话在上限内自动续期。

### 本分支不要做

- 同一资产换模板可并行、dev 下管理 JWT 密钥退回固定 `dev`：这两条是 Phase 5 的返工，走单独分支，不要塞进来。
- Phase 4 遗留的回源 `Content-Length` / 缓存命中 Range 206，以及 `fault_inject.py` 的 MinIO 默认时序、`.gitattributes` 固定 `*.envsh` 为 LF：继续走并行小分支。
- 可选的 `WORKER_CANCEL_CHECK_SECONDS`。
- 字幕 / 多音轨、CMAF / fMP4、H.265 / AV1、多对象存储适配、多 CDN、生命周期冷存储（例如改 storage class 或搬迁到低频桶）。这些是 Phase 6 的后续迭代，本分支不做。
- 旧 Demo 播放链路、`content_key` 迁移、把 Demo 付费集改走加密 Core 资产、Core 加密资产的 iPhone 原生 HLS 改写列表。
- 改 `uk_asset_profile`、改消息 v1、改 Flyway V1–V12、改 `api/common.py` 或 `worker/common.py` 的 `transcode_message()`。
- KMS / 信封加密 / DRM、Webhook 密钥入库加密、回源密钥轮换、mTLS、CDN 刷新、Grafana、播放计数 `GETDEL` 丢增量。
- 完整 RBAC、OAuth、短剧生成、推荐、直播推流。
- 不要重写 Worker 转码流水线，不要换 MQ，不要改 Windows 或 `~/.claude/settings.json`，不要把密钥写进仓库。

### 写方案时的判断

- 第一刀选 Multipart 和封面，是因为路线图的 API 草案和「项目展示重点」已经写了「预签名上传 + Multipart 扩展」，封面又是控制台和短剧业务立刻用得上的；编码格式、多存储、多 CDN 会显著放大 Worker 和分发面，不适合和续传绑在一次合入里。
- 单次 PUT 必须保持默认且行为兼容，避免现有控制台、脚本和测试一次性全改。
- 封面必须放在任务专属前缀里，和 HLS 一起切换、一起 GC。不要写到固定的 `assets/v1/{asset}/poster.jpg`：那种路径的缓存键不带 gen，切封面会打到旧缓存，也会和「先切可播前缀再删旧产物」的规则打架。
- complete 仍然是「先让对象在桶里完整存在，再 HEAD 校验，再在同一事务里建任务」。Multipart 只是把「怎么把字节凑成一个源对象」换了一种协议，不要绕过校验，也不要把分片信息塞进消息 v1。
- 抽帧失败不阻断 READY：封面是增强，不是可播的前提；片头黑帧或极短素材不该让整集转码失败。

### 测试要求（开发时）

每个改动点补测试，并保证现有测试仍通过。有 Java 21 和 Maven 就跑 `cd vod-api && mvn test`（未设 `VOD_TEST_MYSQL_URL` 时 MySQL 用例按设计跳过；能连 MySQL 8.4 就设置后再跑一遍）。跑 `python3 scripts/contract_check.py`、`test_bridge.py`、`test_hls_verify.py`、`test_vod_client.py`、`test_worker_fencing.py`、`test_worker_reliability.py`、`test_dlq.py`、`test_output_gc.py`、`test_playback_contract.py`、`test_api_playback.py`、`test_transcode_template.py` 和新增单测。有 Docker 就用独立项目名起栈（如 `docker compose -p drama-vod-p6 up -d --build`，端口冲突时用 override），跑 `test_fencing_mysql.py`、`scripts/platform_live.py`、`scripts/e2e.py`（旧 Demo 回归）和本分支新增的验收脚本。没跑过的不要声称通过，写明原因。改完后在本分支提交（作者 `TinyPandar <meiyoujunlee@foxmail.com>`），可以 push 到 `origin/plan/phase-6`，但不要合并到 `main`。

## Phase 6 实现说明（第一刀，`plan/phase-6`，已合并到 `main`，`c7ce6c9`）

按上面 10 项做的最小改动。旧 Demo 链路、消息 v1、`uk_asset_profile`、Flyway V1–V12、`api/common.py` / `worker/common.py` 的 `transcode_message()` 都没动；表结构只追加在 `V13__multipart_poster.sql`（H2 MySQL 模式与 MySQL 8.4.10 都跑过）。单次 `mode=put` 的请求与响应字段保持不变。可选小项（雪碧图、单独重抽封面、自动续期）和 Webhook `has_poster` 都**没做**。

**接口**（状态码细节见 `docs/phase-0-contract.md`「Core 上传与资产（Phase 6 新增）」）

| 接口 | 说明 |
|---|---|
| `POST /api/v1/uploads` | 新增可选 `mode`（`put` 缺省 / `multipart`）、`content_type`（只对 multipart 生效）。multipart 响应 `session_id`、`asset_id`、`mode`、`part_size_bytes`、`max_parts`、`expires_in`、`expires_at`，没有 `put_url`。Idempotency-Key 按原会话的方式重放，multipart 重放不会再建一次存储侧上传 |
| `GET /api/v1/uploads/config` | 新增，scope `upload`：`part_size_bytes`、`multipart_threshold_bytes`、`max_parts`、`max_parts_per_request`、`max_object_bytes`、两种会话有效期。控制台在 open 之前需要先知道阈值，所以放在这里，put 的 open 响应就不必加字段 |
| `POST /api/v1/uploads/{id}/parts` | `{"part_numbers":[…]}` → 各 part 的预签名 PUT（分片预签名走公网 presigner），有效期不超过会话剩余时间 |
| `GET /api/v1/uploads/{id}/parts` | 存储侧已收到的 part、etag、size，用于断点续传 |
| `POST /api/v1/uploads/{id}/complete` | put 不变。multipart 必须带 `{"parts":[{part_number,etag}]}`：先 `completeMultipart`，成功后才进入原有 HEAD + `UploadPolicy` + 配额 + 任务/outbox 事务。组合失败 400、会话仍 OPEN |
| `POST /api/v1/uploads/{id}/abort` | 新增。OPEN（含已过期）可取消，multipart 同时在存储侧 abort；COMPLETED 409；重复调用 200；存储不可用 503 |
| 资产与播放 | `GET /assets`、`GET /assets/{id}`、`POST /assets/{id}/playback-token`、`GET /assets/{id}/playback` 追加 `poster_url`（没有为 `null`） |

**各项做法**

1. **存储层**：`ObjectStorage` 加了 `createMultipart` / `presignUploadPart` / `listParts` / `completeMultipart` / `abortMultipart`。`S3ObjectStorage` 用同一个 AWS SDK v2：创建、列出、完成、取消走内网 client，分片预签名走公网 presigner；S3 的 `NoSuchUpload` 映射为 `NoSuchUploadException`，`InvalidPart` / `InvalidPartOrder` / `EntityTooSmall` 等映射为 `MultipartRejectedException`，abort 遇到 `NoSuchUpload` 视为成功。`MemoryStorage`（测试）按 S3 规则模拟：part 升序、etag 一致、非末块不小于 5 MiB，能列出、完成、取消，并记录被 abort 的 upload id。`UnavailableStorage` 新方法同样抛未配置。没有引入别的 SDK。
2. **会话**：V13 给 `vod_upload_session` 加了 `upload_mode`（默认 `put`，列名避开 MySQL 关键字 `mode`）、`multipart_upload_id`、`cleanup_until`（清理领取）、`updated_at`，以及 `(status, expires_at)` 索引。multipart 在 open 事务的最后一步调 `createMultipart`；如果事务之后回滚，提交后回调会把刚建的存储侧上传 abort 掉（best-effort）。multipart 会话有效期单独配置（`VOD_UPLOAD_MULTIPART_SESSION_TTL_SECONDS`，默认 86400，硬上限 604800），从 open 起算、**不续期**；会话内可以反复 `POST /parts` 重签 URL，每次有效期取 `part-url-ttl-seconds` 和会话剩余时间的较小值。
3. **complete / abort 的并发**：`UploadService.complete` 的事务开头改成 `SELECT … FOR UPDATE` 锁会话行，再复核 OPEN / 未过期；abort 也在事务里锁同一行。加锁顺序是会话 → 租户（配额）→ 对象 → 资产 → 任务。真 MySQL 上 10 轮并发「complete 对 abort」，每一轮都只有一方生效：要么 COMPLETED 且有任务（abort 409），要么 ABORTED 且没有任务（complete 409）。multipart 的存储侧组合在这个事务之外进行（`MultipartUploads.complete`），组合成功后再调用原 complete；上一次已组合、但建任务失败（比如配额 403）时重试，存储侧 upload 已不存在、桶里已有对象，就直接走 HEAD 校验。这种情况下即使会话已经过期（仍是 OPEN），`MultipartUploads.complete` 先 HEAD 目标对象、再用 `listParts` 确认 upload 已不存在，两者都成立就照常进入 HEAD + `UploadPolicy` + 配额 + 建任务事务，不再 409（审查 N1：原来过期后这个已组合的对象再也走不完流程）；没组合过的过期会话仍是 409，锁内仍复核 OPEN，所以已 abort 的会话照旧 409。multipart 对象的 ETag（形如 `…-3`）不是整文件 MD5，`checksum` 可能为空，这些都不会导致拒绝。
4. **过期清理**：`UploadCleanup` 定时任务。只处理 `upload_mode='multipart'`、`status='OPEN'`、已过期的会话，用条件 UPDATE 写入 `cleanup_until` 来领取（可以多实例），领到后先 HEAD 目标对象：对象已存在说明存储侧已组合过（只是没建成任务），跳过、保持 OPEN，让客户端重试 complete（计 `cleanup_skipped_assembled`，领取不清，到期后下一轮再看）；否则在存储侧 abort，再改成 `ABORTED`；存储失败时只打日志，领取到期后下一轮重试。**不删除任何对象**：put 会话的过期残留、已组合但没建任务的源对象都留着（见风险）。abort 和过期后，资产都停在 `UPLOADING`（与原来过期会话一样，不能转码）。
5. **封面抽帧**：只对 Core 做。Worker 在 `verify_hls` 之后、复核围栏与上传之前，用参数数组调 `ffmpeg -ss {t} -i src -frames:v 1 …` 抽一帧，宽度上限 1280（不放大），先写到临时文件，确认非空后再移进产物目录，和 m3u8、ts 一起上传（master 仍然最后传），并进入本地清单与远端 list 对账，对不上就按 `STORAGE_ERROR` 重试。抽帧位置默认第 1 秒，片长不足时取片长一半。抽帧失败（无帧、坏文件、超时、空文件）记 `poster_skipped`，任务照常 READY；围栏丢失（`FenceLost`）照常中止。旧 Demo 不抽帧，SQL 也不变。
6. **登记封面**：`fencing.commit_success` 增加 `poster_key` 参数，和 `hls_prefix` / `READY` / 前缀 `ACTIVE` 在同一条 UPDATE、同一事务里写入；键不在本期前缀里时写 NULL。重新转码后封面跟着新前缀走；新一期没出封面时写 NULL，不会继续指向即将被 GC 的旧前缀。封面不进 `vod_storage_object`。
7. **签名地址**：新增 `PosterUrls`，只在 `poster_key` 落在资产**当前** `hls_prefix`（带 gen）里时签发，地址是 `/hls/v1/{sig}/{exp}/{asset}/{gen}/poster.jpg`，签名与该 gen 的 master 相同（Nginx 带 gen 的 location 本来就能匹配，缓存键也带 gen）。playback-token / playback 与 `master_url` 用同一个 `exp`；列表和详情用 `vod.play-ttl-seconds`，同样不超过 `token-ttl-max-seconds`。旧固定前缀、封面键与当前前缀对不上、没有封面时，都返回 `null`。Webhook 事件没改。
8. **控制台**：新增 `console/src/upload.ts`。先读 `GET /uploads/config`，文件大小不小于 `multipart_threshold_bytes` 时走 multipart：先 `GET /parts` 跳过已传分片，3 个并发，每片失败时重签 URL 再试，最多 3 次。某片最终失败后不再领新分片，但会等进行中的分片收尾再报错，这样「继续上传」只补真正缺的分片。全部完成后 complete；「取消上传」会停掉进行中的 PUT 并调用 abort。小文件仍走单次 PUT。资产列表加了封面缩略图列，详情页显示封面，没有封面时显示「—」或提示文字。没有引入 UI 库，构建产物已提交到 `nginx/html/vod-console/`。并发数和重试次数是前端常量；分片大小和阈值都来自服务端。
9. **上传网关（Nginx）**：浏览器要读到每个分片的 `ETag` 才能 complete，所以加了 `Access-Control-Expose-Headers: ETag`。用本机 MinIO 实测发现，MinIO 对带 `Origin` 的预签名 PUT 会自己回 `Access-Control-Allow-Origin`，原来的网关再加一份，浏览器就会看到两个 Allow-Origin，因此用 `proxy_hide_header` 去掉上游自带的 CORS 头。另外查出一个**原有问题**：`limit_except PUT` 在选 location 阶段就把 OPTIONS 切到 deny，`if` 里的预检 204 根本执行不到，所以预检一直是 403，跨源的浏览器直传（包括 Phase 5 控制台的单次 PUT）都会被浏览器拦下。现在改成 `limit_except PUT OPTIONS`，GET 仍然 403。播放侧 location 没动，Range / 回源相关也没动。
10. **compose**：`worker` 透传 `WORKER_POSTER_*`（留空用开发默认值）。新增 `docker-compose.media-live.yml`，只把清理间隔调成 2 秒，供验收脚本使用。

**配置项**（默认值只是开发默认，不是基准或承诺；vod-api 的数值项在 compose 里没有透传，需要时用 override 设置环境变量）

| 名称（环境变量） | 默认 | 含义 |
|---|---|---|
| `VOD_UPLOAD_PART_SIZE_BYTES` | 8388608（8 MiB） | 分片大小；小于 5 MiB 或大于 5 GiB 拒绝启动；`max-object-bytes` 换算成块数超过 10000 也拒绝启动 |
| `VOD_UPLOAD_MULTIPART_THRESHOLD_BYTES` | 0（= 分片大小） | 控制台达到这个大小就走 multipart |
| `VOD_UPLOAD_MULTIPART_SESSION_TTL_SECONDS` | 86400 | multipart 会话有效期，不续期，硬上限 604800 |
| `VOD_UPLOAD_PART_URL_TTL_SECONDS` | 900 | 单个分片 URL 有效期（同时不超过会话剩余时间） |
| `VOD_UPLOAD_MAX_PARTS_PER_REQUEST` | 100 | 一次 `POST /parts` 最多签多少个 part |
| `VOD_UPLOAD_CLEANUP_INTERVAL_MS` / `_INITIAL_DELAY_MS` | 60000 / 30000 | 过期 multipart 清理的间隔与首次延迟 |
| `VOD_UPLOAD_CLEANUP_BATCH_SIZE` / `_CLAIM_SECONDS` | 50 / 300 | 每轮条数 / 领取时长（失败后过了领取时长再试） |
| `VOD_UPLOAD_SESSION_TTL_SECONDS` | 900（不变） | put 会话有效期 |
| `WORKER_POSTER_ENABLED` | 1 | 是否抽帧 |
| `WORKER_POSTER_AT_SECONDS` | 1.0 | 抽帧位置；不小于片长时取片长一半 |
| `WORKER_POSTER_TIMEOUT_SECONDS` / `WORKER_POSTER_MAX_WIDTH` | 60 / 1280 | 抽帧超时 / 封面宽度上限 |

**新指标**（不带 URL、租户或密钥标签）：vod-api `vod_upload_multipart_total{event=created|completed|complete_rejected|aborted|put_aborted|expired_aborted|cleanup_skipped_assembled|cleanup_failed}`；Worker `vod_worker_poster_total{result=ok|skipped}`。前者在本机 jar 的 `/actuator/prometheus` 里看到了，后者在 `prometheus_client` 的输出里看到了。

**本分支实际跑过的测试**（2026-11-12，macOS arm64，本机**没有 Docker**，也没有可供自动化的 Chrome / Chromium）

- `cd vod-api && mvn test`：共 136 个用例（原 111 个 + 新增 25 个）。不设 `VOD_TEST_MYSQL_URL` 时 0 失败，10 个 MySQL 用例跳过；设到本机 MySQL 8.4.10 再跑，136 个全过、0 跳过（这一轮 Flyway V1–V13 在 MySQL 上执行）。
  - `MultipartUploadTest`（18 个）：put 响应字段集合不变；config；创建；重放不再 create；列出已传分片后续传；complete 后建任务；缺 part、错 etag、顺序错、非末块太小都 400 且不建任务，改正后可以重试；组合成功但配额 403 时会话仍 OPEN，放开配额后重试走 HEAD；put 会话调分片接口 409；part 号校验；过期 409；part URL 有效期不超过会话剩余时间；跨租户全部 404；abort 后存储侧被取消、之后不能再用；已 complete 不能 abort；存储故障时 abort 返回 503、会话保持 OPEN；清理只处理过期 OPEN 的 multipart，存储失败保留领取、到期后重试，COMPLETED 会话和已校验的源对象不碰；别的实例已领取时本实例跳过。
  - `PosterUrlTest`（3 个）：详情、列表、playback-token、playback 的签名与 `HlsSigner` 一致，并且与 master 同签名；没有封面、封面键不在当前 gen、旧固定前缀、UPLOADING 都返回 `null`，不报 500；跨租户 404。
  - `Phase6MysqlTest`（3 个，只跑真 MySQL）：multipart 全流程；10 轮并发 complete 对 abort；清理在 MySQL TIMESTAMP 下的比较与领取。
  - `S3PresignTest` 新增 1 个：分片 URL 用公网地址，带 `uploadId`、`partNumber` 和有效期。
- Python 3.12：`contract_check`、`test_bridge`、`test_hls_verify`、`test_vod_client`、`test_worker_fencing`（7）、`test_worker_reliability`（39）、`test_dlq`（5）、`test_output_gc`（9）、`test_playback_contract`、`test_api_playback`（8）、`test_transcode_template`（3）全过；新增 `test_poster.py` 16 个全过，覆盖：封面进清单并先于 master 上传；远端缺封面或封面大小不对时按 STORAGE_ERROR 重试、不提交；抽帧失败、超时、空文件时仍然 ok 且 `poster_key=None`；关闭开关后不抽帧；围栏丢失时丢弃；旧 Demo 不抽帧；成功事务 SQL；键不在当期前缀时写 NULL；另有 5 个用**本机真实 ffmpeg** 的用例，测竖屏、短片取中间帧、横屏缩到 1280、纯音频和垃圾文件返回 False。
- **N1 补丁**（2026-11-13，box 上 Linux、MySQL 8.4，审查后补）：`MultipartUploadTest` 新增 2 个（组合成功但配额 403、会话过期后，再 complete 仍按 HEAD + 配额走、清理不标 ABORTED、放开配额后建任务；清理跳过已组合会话、照旧 abort 没组合的会话，被主动 abort 后 complete 仍 409），`Phase6MysqlTest` 新增 1 个（同一流程在真 MySQL 上）。`mvn test` 共 139 个：不设 `VOD_TEST_MYSQL_URL` 时 0 失败、11 个跳过；设到 MySQL 8.4 时 139 个全过、0 跳过。
- `test_fencing_mysql.py` 连本机 MySQL 8.4.10：30 个全过（新增 2 个：封面跟着 ACTIVE 前缀走，重转没封面时写 NULL；围栏丢失时不写封面）。
- `nginx_local_check.py`（本机 Homebrew nginx）：42/42（原 33 个 + 新增 9 个：gen 目录下的 `poster.jpg` 能取到 200 image/jpeg、immutable，换 gen 后 403；上传网关能转发带 query 的分片 PUT、保留 ETag、只有一份 Allow-Origin、暴露 ETag、预检 204、GET 仍 403）。改动前同一份检查里，后 3 项（单份 Allow-Origin、暴露 ETag、预检 204）是失败的。
- **本机冒烟**（不是 compose 栈）：
  - 环境：`vod-api` 打包后的 jar，连本机 MySQL 8.4 空库（Flyway V1–V13）；对象存储是本机 MinIO（Homebrew bottle `2025-10-15T17-29-55Z`，只解压到 `.phase3-tools/`，没有安装进系统）；用仓库 nginx 模板渲染出本机边缘（复用 `nginx_local_check.render`），`/hls/v1` 回源到一个按 `api/origin_path.py` 读 MinIO 的小桩，上传网关指向 MinIO，`S3_PUBLIC_ENDPOINT` 指向网关；Worker 是一个轮询脚本，代替 RabbitMQ 消费者调用**真实的** `worker.process`（真实 ffmpeg 转码与抽帧、真实上传 MinIO、真实 MySQL 成功事务）。
  - `scripts/media_live.py` 不经边缘时 24/24（跳过边缘取图）；经本机边缘 27/27。覆盖：约 19–25 MB 的视频切 3–4 片走网关直传；断点续传；错 etag 返回 400；READY；三个接口都有 `poster_url`；经边缘取到 200 image/jpeg、篡改签名 403；封面在 `{job}-{claim_version}/` 下，固定路径 `assets/v1/{asset}/poster.jpg` 不存在；complete、abort、过期清理之后，MinIO 的 `list_multipart_uploads` 都为空；默认 put 路径仍可用。
  - 在同一套环境里重新转码：新前缀 `1-3` 变成 ACTIVE、`poster_key` 跟着切过去，旧前缀 `1-1` 变成 GARBAGE；手动核对过 `poster_url` 的签名与 Nginx `secure_link_md5` 的算法一致，封面是 720×1280 的 MJPEG。
  - 控制台：本机没有可自动化的浏览器，**没在浏览器里点过**。用 esbuild 把控制台真实的 `upload.ts` / `api.ts` 打包，在 Node 22 里对着本机边缘跑了 7 项全过：阈值来自服务端；第 1 片之后模拟断网 → 服务端保留已传分片 → 「继续上传」只补缺的 3 片 → complete 拿到任务；小文件走 put；取消后再调分片接口 409。第一次跑时发现「某片失败后其它并发分片还在继续传、过早报错」，导致续传时把所有分片又传了一遍，已修。Node 不检查 CORS，浏览器侧的 CORS 只靠上面的 nginx 检查和 MinIO 实测来保证。`vue-tsc` 和生产构建都通过。
- **没跑**（没有 Docker）：`docker compose -p drama-vod-p6 … up`、compose 栈上的 `scripts/media_live.py`、`scripts/e2e.py`、`scripts/platform_live.py`、`scripts/playback_live.py`；真实 RabbitMQ 消费与 Worker 的 GC 线程删除旧封面（只在 MySQL 层看到旧前缀进入 GARBAGE）；compose 里的 chainguard MinIO 镜像（本机用的是上面那个版本）；控制台在真实浏览器里的交互和 CORS 表现。

```bash
# 需要 Docker：独立项目名；端口冲突时在 .env 改 HTTP_PORT/UPLOAD_PORT 或加 override
docker compose -p drama-vod-p6 -f docker-compose.yml -f docker-compose.media-live.yml up -d --build
COMPOSE_PROJECT_NAME=drama-vod-p6 python3 scripts/media_live.py     # 需要本机 ffmpeg；有 PyMySQL 才测过期清理
python3 scripts/e2e.py
COMPOSE_PROJECT_NAME=drama-vod-p6 VOD_ADMIN_USERNAME=… VOD_ADMIN_PASSWORD=… python3 scripts/platform_live.py
```

**剩余风险**

- **不删除源对象**：put 会话过期后，桶里可能留着已经 PUT、但从没 complete 的源对象；multipart 已组合成功、但被 abort 的源对象也会留着。这两种都没有清理，目前只 abort 未完成的分片。multipart 已组合、没建任务就过期的会话不再卡死（过期后仍可 complete，清理也不会把它标 ABORTED），但客户端如果一直不重试，会话会一直停在 OPEN、对象一直留着，清理每过一个领取时长（默认 300 秒）会再 HEAD 一次。
- multipart 会话不续期：超过有效期（默认 24 小时）还没传完，只能取消重开。open 事务回滚时的存储侧 abort 是 best-effort，失败只打日志，可能留下一个没有会话指向的未完成上传（MinIO 自身的过期策略之外，没有别的兜底）。
- 加密资产的封面是**明文 JPEG**（只有签名 URL 保护，不需要内容密钥）。如果付费内容连首帧都不能外露，需要给加密资产关掉封面，这个开关本分支没做。
- 已 READY 的旧资产没有封面，要重新转码才会生成；没有「只重抽封面」的接口。重新转码时没出封面会写 NULL，旧封面不会保留。
- complete 现在按会话行串行：同一会话的并发 complete 会排队，HEAD 也在锁内（原来就在同一个事务里）。
- abort 或过期之后资产会一直停在 `UPLOADING`，资产列表里会看到这些行。
- 控制台没有在浏览器里实际点过；分片并发数（3）和每片重试次数（3）是前端常量。
- 上传网关的 CORS 只信请求里的 `Origin`（这是原有做法，本分支只修了重复头、暴露 ETag 和预检）。

## 分支方案：CI（GitHub Actions 跑现有测试）

本分支：`plan/ci`，基线 `main` = `e1e211f`（Phase 5 和返工都已合并）。目前仓库没有任何 CI，测试全靠本地手跑。这一刀只做「每次 push 和 PR 自动跑现有测试」，不新写业务测试，也不改业务代码。

### 要做的事

1. 新增 `.github/workflows/ci.yml`，触发：`push` 到任意分支、`pull_request` 到 `main`，外加 `workflow_dispatch`。设 `concurrency`，同一分支新提交取消旧运行。权限只给 `contents: read`。
2. **job `vod-api`**：`ubuntu-latest`，`actions/setup-java`（Temurin 21，开 Maven 缓存），起 `mysql:8.4` service（root 空口令或固定测试口令，带健康检查），设置 `VOD_TEST_MYSQL_URL` / `VOD_TEST_MYSQL_USER`（/ `VOD_TEST_MYSQL_PASSWORD`）后在 `vod-api` 下跑 `mvn -B test`。这样 H2 用例和 `Phase5MysqlTest`、`MysqlSemanticsTest` 一次跑完，**MySQL 用例不能被跳过**（在步骤里检查 surefire 报告里这两个类 skipped=0，或用等价办法让「没连上库而全跳过」变成失败）。失败时上传 `target/surefire-reports` 作为 artifact。
3. **job `python`**：`actions/setup-python`（版本与 `api/Dockerfile`、`worker/Dockerfile` 一致），装 `api/requirements.txt`、`worker/requirements.txt` 和测试要用的 `pymysql`；同样起 `mysql:8.4` service。依次跑 `scripts/contract_check.py` 和 `scripts/test_*.py` 全部 11 个（`test_fencing_mysql.py` 用 service 的端口）。任一脚本非零退出就失败，不要 `|| true`。需要 ffmpeg 的用例目前都是假的 ffmpeg，如果实际需要就 `apt-get install ffmpeg`，不需要就别装。
4. **job `console`**：`actions/setup-node`（写明主版本，开 npm 缓存），在 `console` 下 `npm ci && npm run build`。现在 `npm ci` 报锁文件和 `package.json` 不同步（Phase 5 审查 N7，main 上本来就有），本分支顺手用 `npm install` 重新生成 `package-lock.json` 修掉，**不升级依赖版本范围**。可选：构建完 `git diff --exit-code` 检查提交里的构建产物没过期（如果仓库确实提交了产物）。
5. 三个 job 并行；README「检查」一节补一行 CI 说明和徽章（可选），写清楚 CI 里跑什么、不跑什么。

### 本分支不要做

- 不跑需要整套 compose 的脚本：`e2e.py`、`playback_live.py`、`platform_live.py`、`core_live.py`、`drama_vod_live.py`、`fault_inject.py`、`kill_recover_submit.py`、`browser_check.py`、`nginx_local_check.py`。Docker 集成测试以后单独一刀（可能是手动触发或 nightly）。
- 不改业务代码和现有测试的断言；如果 CI 环境下某个测试暴露问题（比如时序、时区、路径），先在 README 实现说明里记下来，最小修，修法写清楚，不要用跳过掩盖。
- 不加发布、镜像推送、部署、代码覆盖率服务、需要 secrets 的步骤。
- 不改 Phase 6 分支（`plan/phase-6`）；它以后合 main 时自然带上 CI。

### 测试要求

推上去后看 Actions 实际结果：三个 job 都绿，并在实现说明里记录 run 链接、`vod-api` 用例数和 MySQL 用例确实没跳过。故意改坏一个断言在临时分支验证 CI 会红（验证完删掉那个临时分支，不要合）。

## CI 实现说明（`plan/ci`，已合并到 `main`，`ce86b9a`）

改动只有三处：新增 `.github/workflows/ci.yml`；重新生成 `console/package-lock.json`；README「检查」一节补 CI 说明和徽章。没有改业务代码，也没有改测试和断言。

- **触发与权限**：`push` 任意分支、`pull_request` 到 `main`、`workflow_dispatch`；`concurrency` 按 workflow + ref 分组，新提交取消旧运行；`permissions: contents: read`。没有发布、部署、覆盖率服务，也没有 secrets。服务容器里的 MySQL root 口令 `ci-root` 只在 CI 用，不是密钥。
- **`vod-api`**：Temurin 21 + Maven 缓存，`mysql:8.4` 服务容器（`mysqladmin ping` 健康检查，端口随机映射），`VOD_TEST_MYSQL_URL` / `USER` / `PASSWORD` 指向它，跑 `mvn -B test`。之后用一段 Python 读 `target/surefire-reports/TEST-*.xml`，把总用例数写进 job summary；`MysqlSemanticsTest`、`Phase5MysqlTest` 的报告缺失、`tests=0` 或 `skipped≠0` 都算失败，这样「没连上库导致整类跳过」就会变红。失败时上传 `target/surefire-reports` 作为 artifact（`vod-api-surefire-reports`）。
- **`python`**：Python 3.12（与两个 Dockerfile 的 `python:3.12-slim` 一致），pip 缓存，装 `api/requirements.txt`、`worker/requirements.txt`（两者已经包含 `pymysql`），另装 `httpx==0.28.1`。同样起 `mysql:8.4`，`MYSQL_HOST` / `MYSQL_PORT` 指向服务端口。依次跑 `scripts/contract_check.py` 和 `scripts/test_*.py`（glob，当前 11 个，共 12 个脚本）：每个脚本单独分组输出，任何一个非零退出或输出里出现 `skipped=` 都算失败，全部跑完后再统一给结论，不用 `|| true`。用例里的 ffmpeg 都是假的（`build_ffmpeg` 被替换），所以没装 ffmpeg。
- **`console`**：Node 24 + npm 缓存，`npm ci && npm run build`（含 `vue-tsc` 类型检查）。构建后检查 `nginx/html/vod-console/` 没有改动、也没有新增未跟踪文件，也就是确认提交的构建产物和源码一致。

CI 落地时暴露并修掉的问题（都不涉及业务代码和测试断言）：

1. **锁文件不同步（Phase 5 审查 N7）**：`package-lock.json` 缺少 rollup 4.68.0 的可选平台包 `@rollup/rollup-android-x64@4.68.0`。npm 10（Node 22 自带）的 `npm ci` 不检查这一项，所以本地能过；npm 11（Node 24 自带）会报 `Missing: @rollup/rollup-android-x64@4.68.0 from lock file`。修法：用 npm 11 在 `console/` 下跑 `npm install` 重新生成锁文件。结果只多了这一个包条目（+14 行）；`package.json` 和所有已锁定的版本都没变，没有升级依赖范围。npm 10 和 npm 11 的 `npm ci` 都能通过，本地重新构建出的产物和仓库里的逐字节一致。
2. **`test_api_playback.py` 缺 httpx 会整类跳过**：它在 `ImportError` 时 `skipIf`，而 FastAPI 的 `TestClient` 需要 httpx，两个 requirements 都没有它（它不是运行时依赖）。CI 里单独装 `httpx==0.28.1`（与 fastapi 0.115.6 / starlette 0.41.3 搭配能跑通），并加了「出现 skipped 即失败」的检查。没有改 requirements 文件。
3. **workflow 自身**：第一次推送（`198d935`）时把 `${{ job.services.mysql.ports['3306'] }}` 写在了 job 级 `env`，但 job 上下文只能在步骤级用，GitHub 在解析阶段直接拒绝（[run 43251392126](https://github.com/TinyPandar/drama-vod/actions/runs/43251392126)，0 秒、没有 job 启动）。`ef70fc1` 把这些变量挪到对应步骤的 `env` 里。

除此之外，CI 环境下没有测试出现时序、时区或路径问题，第一次真正跑起来就三个 job 全绿。

**Actions 结果**

- 绿：[run 43251558134](https://github.com/TinyPandar/drama-vod/actions/runs/43251558134)（`ef70fc1`），`console` 12s、`python` 54s、`vod-api` 1m59s。
  - `vod-api`：26 个测试类，**115 个用例**，skipped=0、failures=0、errors=0。其中 `MysqlSemanticsTest` 5 个、`Phase5MysqlTest` 3 个，都连 `mysql:8.4` 真跑，skipped=0。
  - `python`：12 个脚本全部 exit 0，没有 skipped。unittest 用例数：`test_api_playback` 8、`test_dlq` 5、`test_fencing_mysql` 28（日志打印 `mysql 8.4.13 db vod_fence_test`）、`test_output_gc` 9、`test_transcode_template` 3、`test_worker_fencing` 7、`test_worker_reliability` 39，共 99 个；`contract_check`、`test_bridge`、`test_hls_verify`、`test_playback_contract`、`test_vod_client` 是断言脚本，不报用例数，以退出码为准。
  - `console`：`npm ci` 装了 52 个包，构建产物与仓库一致。
- 红（验证用）：临时分支 `tmp/ci-red-check` 把 `MysqlSemanticsTest.connectorJCountsMatchedRowsByDefault` 的 `assertEquals(1, …)` 改成 `assertEquals(2, …)`，结果是 [run 43252101699](https://github.com/TinyPandar/drama-vod/actions/runs/43252101699)：`vod-api` 失败（`expected: <2> but was: <1>`，也说明 MySQL 用例确实在跑），surefire 报告 artifact 已上传；`python`、`console` 仍是绿的。验证完已经删掉这个分支（本地和 origin 都删了），没有合并。

`ubuntu-latest` 运行时带一条平台提示：这个标签从 2026-10-19 起陆续切到 Ubuntu 26。目前不影响，以后如果镜像变化导致失败，再考虑固定成 `ubuntu-24.04`。

## 分支方案：Phase 5 平台化控制台

本分支：`plan/phase-5`，基线 `main` = `9cab039`（Phase 4 已合并）。下面先是方案原文，实现情况见紧接着的「Phase 5 实现说明」。

协作方式：其他 agent 请在**本分支**上按下面清单做最小改动开发；不要自行合并到 `main`，也不要另开平行分支。开发完后由「短剧视频服务器开发bot」检查，没问题再合并。Range 响应头（缓存命中后要回 206）和故障注入脚本时序是并行小分支，不要放进本分支。

对照文档：[路线图](docs/panda-vod-roadmap.html) 第 06 节 API 草案（`POST /webhooks`、`GET /usage`、`POST /assets/{id}/transcodes`、管理端与租户 API 分开授权）、第 08 节（API Key 轮换、Spring Security + JWT、Webhook 防 SSRF 与 DNS 重绑定、限流与配额）、第 09 节 Phase 5、第 10 节第 1 条验收（租户互相不可见）。目标：**做出可演示的多租户点播平台**：控制台能管租户和密钥、能按模板重新转码、转码结果能可靠回调，租户之间互相看不见。

### 已经有的，不要重做

这些是 Phase 1–4 留下的雏形，本分支在其上做最小改动，不要推倒重来。

- Vue 3 控制台雏形：`console/`（Vite，`base` 为 `/vod-console/`，构建到 `nginx/html/vod-console/`）。现在是在页面里粘贴 API Key，能直传、刷新资产和任务列表、看当日用量、登记 Webhook，没有登录，也没有租户、密钥、模板、任务详情和投递记录。
- 租户与 API Key：`vod_tenant`、`vod_api_credential`（只存哈希、scopes、status、`expires_at`），`ApiKeyFilter` 校验 `X-Api-Key` / Bearer。没有创建、撤销、轮换接口，明文不会只展示一次——因为根本还没有管理接口。
- 存储配额：`vod_tenant.storage_quota_bytes` / `storage_used_bytes`，`UsageService.reserveStorage` 在 complete 事务里原子占用（V8）。
- 日用量：`vod_usage_daily`（存储、播放次数、转码任务数），`GET /api/v1/usage` 只返回「今天」三列，不含配额和已用存储。
- Webhook 登记：`POST/GET /api/v1/webhooks`（`PlatformController`）。登记时校验 HTTPS、拒绝 localhost / 内网字面量 / 云 metadata；创建时返回 `secret`，列表不再返回。`WebhookSigner` 用 HMAC-SHA256，请求头 `X-Vod-Signature`。
- Webhook 投递现状：`UploadService.publishAfterCommit` 在事务提交后**同步**调用 `webhooks.dispatch(..., "transcode.requested", body)`，body 还是转码消息；只试一次，失败写入 `vod_webhook_delivery` 后不会重试。成功、失败、取消都不会发事件。`LiveWebhookIo` 先按主机名解析再按主机名连接，解析和连接之间 DNS 可能指向内网（重绑定窗口还在）。
- 转码任务唯一键 `(asset_id, profile)`，默认 profile 为 `ladder_v1`（Phase 0 冻结，本分支不得删这个唯一键）。重新转码必须复用同一行，靠 `claim_version + 1` 打开新的输出前缀（Phase 4 已经按这个设计做了切换和清理）。
- 取消：`POST /api/v1/jobs/{id}/cancel`。失败重放目前只有运维脚本 `scripts/dlq.py`，不是公开 API。
- 播放、鉴权、Outbox、围栏、任务专属前缀：Phase 4 已合并，不要改行为。`vod_outbox` 只给转码投递 RabbitMQ 用，Webhook **不要**复用这张表：`OutboxPublisher` 会把 `PENDING` 行发到转码队列。

### 本分支要做的

建议按顺序做。新表结构用 Flyway **V12 及之后**，不要改 V1–V11。SQL 要同时兼容 H2 MySQL 模式和 MySQL 8.4。`mysql/init.sql` 只管旧 Demo 表。旧 Demo 链路按 `docs/phase-0-contract.md` 冻结。消息 v1 契约不改，模板和事件都从 `job_id` 查库。TTL、重试次数、限流速率、配额默认值、JWT 有效期都写成配置项，默认值不是基准或承诺。不要编造性能数字。密钥不要写进仓库或输出。

1. **管理端鉴权：Spring Security + JWT**
   现状：`vod-api` 没有 `spring-boot-starter-security`，租户接口全靠 `ApiKeyFilter`，管理接口不存在。
   要改：引入 Spring Security，但不要把现有 API Key 鉴权推倒重写。`/api/v1/admin/**` 用 JWT（登录接口除外）；现有租户接口（`/api/v1/uploads`、`/assets`、`/jobs`、`/webhooks`、`/usage`、播放 token 等）继续走 API Key；`/api/v1/healthz`、`/api/v1/qoe`、`/keys/v1/**` 维持现有放行。`POST /api/v1/admin/login` 校验管理用户（密码只存哈希，开发环境可用环境变量种子，不要把默认口令写进镜像说明以外的代码仓库）。JWT 用新密钥 `VOD_ADMIN_JWT_SECRET`，走现有 `SecretGuard`：非 dev 时空、等于 `dev` 或过短就拒绝启动，带版本前缀，过期时间可配。管理接口拿到的身份不能当成租户 API Key 用，租户 Key 也不能调用 `/admin/**`（隔离要有测试：用租户 Key 调管理接口 401/403，用管理 JWT 调租户上传接口 401）。不做完整 RBAC、不做 OAuth、不接第三方登录。

2. **租户与 API Key 管理接口**
   要改：管理端提供租户的创建、列表、启用/停用，以及给指定租户创建、列出、撤销、轮换 API Key。创建和轮换只在响应里展示一次明文，库里只存哈希；支持 scopes、过期时间。停用租户后，该租户所有 Key 不能再调租户接口。设置或修改存储配额、并发转码上限、每日转码次数上限（后两项见第 7 项，列加在 `vod_tenant` 上即可）。列表不要返回哈希或明文。这些接口全部在 `/api/v1/admin/**`，鉴权走第 1 项。现有 `DevCredentialSeeder` 和开发用 `dev-demo-key` 保留，方便本地和旧测试。

3. **Webhook 事件与可靠投递**
   现状：只在上传完成之后同步发一次 `transcode.requested`，不重试；成功/失败/取消没有事件。
   要改：
   - 事件至少包括 `transcode.succeeded`、`transcode.failed`；`transcode.requested` 可以保留，但改成与另外两个相同的异步投递，不要在 `afterCommit` 里直接 HTTP。取消成功后发 `transcode.failed`（payload 带 `reason=cancelled`）或单独的 `transcode.cancelled`，选一个写进契约并保持一致。
   - 成功、失败事务在 **Worker**（`fencing.py` 里写 `SUCCEEDED` / `FAILED` 的同一事务）插入 Webhook 事件；取消在 **vod-api** 的取消事务里插入。不要在事务里发 HTTP。
   - 新建事件表（例如 `vod_webhook_event`），**不要**写入 `vod_outbox`。投递表在 V3 `vod_webhook_delivery` 上加列，或新建并废弃同步写入路径：状态、`attempts`、`next_attempt_at`、领取租约、`response_code`、截断后的错误信息、请求体引用。
   - Java 侧做与 `OutboxPublisher` 类似的投递循环：条件 UPDATE 领取、指数退避加 jitter、超过次数标记最终失败；多实例安全。登记、查询、停用端点的租户接口补齐（至少能停用；删除可选）。事件 payload 用稳定 JSON（含 `event_type`、`job_id`、`asset_id`、`occurred_at` 等），签名仍用 `WebhookSigner` + `X-Vod-Signature`。
   - 投递超时、响应体大小上限写成配置。限流见第 7 项（按租户限制出站频率）。提供管理或租户侧的投递记录查询（不要返回签名密钥）。

4. **Webhook SSRF 与 DNS 重绑定**
   现状：`WebhookUrlGuard` 拒绝明文 HTTP、userinfo、localhost、常见 IPv4 内网字面量和 `169.254.169.254`；投递前会再解析一次，但 `LiveWebhookIo.post` 仍向**主机名**发请求，校验和连接之间可以被重绑定。IPv6 字面量几乎没拦。
   要改：解析 → 校验每个地址都是公网（含 IPv6：回环、链路本地、ULA `fc00::/7`、IPv4 映射的内网地址）→ **连接到这次校验过的 IP**，同时用原主机名做 SNI / `Host` / 证书校验。不要先校验再按主机名让运行时重新解析。登记时和每次投递时都要走这套检查。开发环境若要打到本机回调，用显式开关（仅 `VOD_ENV=dev`）放行，默认关闭。补测试：假 DNS 第一次返回公网、连接前改成 `127.0.0.1` 时必须被拦住或根本连不到内网。

5. **转码模板与重新转码：`POST /api/v1/assets/{id}/transcodes`**
   现状：没有模板表；Worker 码率梯队写死在 `worker.py`；`JobGate` / `uk_asset_profile` 保证每个资产每个 profile 只有一行。
   要改：V12+ 建 `vod_transcode_template`（系统默认模板 + 租户模板）。默认模板的 profile 仍叫 `ladder_v1`，参数与当前 Worker 梯队一致，避免老资产行为悄悄变掉。租户可列出可用模板；管理端可改租户模板（系统默认是否可改写成配置，默认不要在演示里改系统模板）。
   `POST /api/v1/assets/{id}/transcodes`（scope 与资产写操作一致，支持 `Idempotency-Key`）：指定模板发起转码。跨租户走现有不可见处理；资产不存在或未上传完成（没有源对象）返回 4xx。若该 `(asset_id, profile)` 已有任务且状态是 `PENDING` / `RETRYING` / `RUNNING`，返回 409，不新建行。若任务是 `SUCCEEDED` / `FAILED` / `CANCELLED`，**复用这一行**：在事务里把状态打回 `PENDING`，`claim_version + 1`，清空租约和错误，写入新的 outbox（消息 v1 不变），并按 Phase 4 规则插入新的 `WRITING` 前缀。不要删除 `uk_asset_profile`，不要另开第二行相同 profile 的任务。加密资产重新转码沿用 `vod_content_key` 里已有的 key，不要另写一把（本分支不做密钥轮换）。已 READY 的旧 Core 加密资产走这次重新转码后，应写出 `/keys/v1/` 和新的任务专属前缀，这是把它们修到可播的正规路径。Worker 按 `job_id` 读模板参数，不要把梯队塞进消息体。

6. **租户侧重试：`POST /api/v1/jobs/{id}/retry`**
   把 `scripts/dlq.py replay` 的规则收成公开接口：只接受本租户、库里为 `FAILED` 的 Core 任务（以数据库为准，不转发死信原消息）；重置为 `PENDING`、资产回到可转码状态、写新 outbox。已在跑或已成功的任务 409；跨租户 404。是否允许从 `CANCELLED` 重试写成配置，默认允许并在文档里写清。支持 `Idempotency-Key`。运维脚本可以改调同一套服务逻辑，但不是本项必做。

7. **API 限流与基础配额**
   现状：只有存储配额；Nginx 对 `/keys/v1/` 和 QoE 有 `limit_req`；租户 API 没有整体限流。
   要改：租户 API（按 API Key / 租户）和管理登录（按 IP）做可配置限流。单实例内存桶也可以，但机器上已有 Redis，优先用 Redis，这样多实例 vod-api 限额是共享的；用内存时要在 README 写明「只对单实例有效」。超限返回 429 和 `Retry-After`。配额在存储之外增加：同时 `RUNNING` 的转码任务数、每日新建/重转次数；超额 409 或 429（选一个并在契约里写死）。出站 Webhook 也按租户限速，避免回调把下游或自身打满。默认值一律可配，dev 下要松到现有测试不必改一堆环境变量就能过。

8. **用量查询补全**
   `GET /api/v1/usage` 在现有今日计数之外，返回配额、已用存储、并发占用、可选的按天历史（例如 `?days=`，上限可配）。管理端可查指定租户。播放计数的 Redis `GETDEL` 丢增量问题本分支不做。

9. **Vue 控制台升级**
   继续用 Vue 3 + TypeScript + Vite，构建产物仍输出到 `nginx/html/vod-console/`。加上路由，但不要引入大型 UI 框架。页面要能演示完整平台流程：
   - 管理登录（JWT），不要再把管理口令写进前端源码；
   - 租户列表与创建、配额编辑；
   - API Key 创建 / 撤销 / 轮换（明文只出现一次）；
   - 资产列表与上传、资产详情、对 READY 资产申请播放 token 并试播；
   - 任务详情：取消、重试、按模板重新转码；
   - 模板列表；
   - Webhook 登记、停用、投递记录；
   - 用量与配额。
   控制台调管理接口用 JWT，调租户接口用该租户的 API Key（登录后由管理员创建或选用）。开发模式的 Vite 代理保留。构建后的静态文件提交进仓库的规则与现在一致（现在会提交 `nginx/html/vod-console/`），避免演示栈还要现场 `npm run build`。本项不改旧 Demo 播放页，只保留通往控制台的入口。

10. **验收脚本与文档**
    新增 `scripts/platform_live.py`（需要 Docker 栈）：创建租户与 Key、上传并等到 READY、收到 `transcode.succeeded`（脚本起一个公网可达或 dev 开关下的本地 HTTPS/HTTP 接收端）、跨租户资产 404、伪造/过期管理 JWT 不能调管理接口、租户 Key 不能调 `/admin/**`、重新转码后 `claim_version` 增加且旧前缀进入清理、失败任务可重试、限流或配额至少能测到一条超限路径、SSRF 地址登记被拒。更新 `docs/phase-0-contract.md` 的 Core 部分（旧 Demo 条目保持冻结原文），README 补新接口、配置项、事件名和指标（若有投递成功/失败计数，用 Micrometer，标签不要带 URL 或密钥）。

**可选小项（不做不影响验收，做了单独说明）**：dev 下用开关放行 HTTP Webhook，方便 `platform_live.py` 不配证书；控制台构建进 `vod-api` 或 Nginx 镜像的一致性检查（例如 CI 或脚本核对 `console/` 与 `nginx/html/vod-console/` 是否同步）。

### 本分支不要做

- Phase 4 遗留的回源 `Content-Length` / 缓存命中 Range 206，以及 `fault_inject.py` 里 MinIO 场景的默认时序、`.gitattributes` 固定 `*.envsh` 为 LF：这些走并行小分支，不要塞进本分支。
- 可选的 `WORKER_CANCEL_CHECK_SECONDS`（Phase 4 就没做）。
- 旧 Demo 播放链路的任何改动、`content_key` 迁移、把 Demo 付费集改走加密 Core 资产。
- 改 `uk_asset_profile` 或消息 v1，或改 Flyway V1–V11。
- KMS / 信封加密 / DRM / Webhook 密钥入库加密（密钥继续只在创建时返回一次即可）。
- 回源密钥轮换、mTLS、CDN 刷新、Grafana、播放计数 `GETDEL` 丢增量、Multipart / 缩略图 / 字幕 / CMAF / H.265 / 多存储 / 多 CDN / 冷存储（Phase 6）。
- 完整 RBAC、OAuth、第三方登录、多管理角色。
- 短剧生成、推荐、直播推流。这些不属于点播中台本阶段。

### 写方案时的判断

- 重新转码复用 `(asset_id, profile)` 这一行，而不是拆唯一键。Phase 4 的输出前缀已经是 `{job_id}-{claim_version}`，`claim_version + 1` 自然打开新目录；成功事务只允许自己的 `WRITING` 前缀变成 `ACTIVE`，旧的 `ACTIVE` 会变成 `GARBAGE`。
- Webhook 事件必须进独立表，不能借用 `vod_outbox`。
- DNS 重绑定的补丁点在 `LiveWebhookIo`：要钉住已校验的 IP 再连接，只靠「先解析再检查」不够。
- 管理端和租户端从鉴权上分开，控制台只是这两类 API 的界面。租户模型按业务线隔离（长视频 / 短视频 / 短剧可以是不同租户），本阶段不实现业务线专属逻辑。
- `docker-compose.yml` 里仍写死了部分宿主端口，本分支的验收栈继续用独立项目名和 override，不要改已有容器和数据卷。

### 测试要求（开发时）

每个改动点补测试，并保证现有测试仍通过。有 Java 21 和 Maven 就跑 `cd vod-api && mvn test`（当前 main 上是 82 个用例；未设 `VOD_TEST_MYSQL_URL` 时 MySQL 用例按设计跳过；能连 MySQL 8.4 就设置后再跑一遍）。跑 `python3 scripts/contract_check.py`、`test_bridge.py`、`test_hls_verify.py`、`test_vod_client.py`、`test_worker_fencing.py`、`test_worker_reliability.py`、`test_dlq.py`、`test_output_gc.py`、`test_playback_contract.py`、`test_api_playback.py` 和新增单测。有 Docker 就用独立项目名起栈（如 `docker compose -p drama-vod-p5 up -d --build`，端口冲突时用 override），跑 `test_fencing_mysql.py`、`scripts/platform_live.py`、`scripts/e2e.py`（旧 Demo 回归）；`playback_live.py` 因 Range 问题已知会有一项失败，不要为了让它全过而在本分支改回源。没跑过的不要声称通过，写明原因。改完后在本分支提交（作者 `TinyPandar <meiyoujunlee@foxmail.com>`），可以 push 到 `origin/plan/phase-5`，但不要合并到 `main`。



## Phase 5 实现说明（已合并到 `main`，`248b6e3`；返工 `e1e211f`）

旧 Demo 链路、消息 v1、`uk_asset_profile`、Flyway V1–V11、`mysql/init.sql` 都没动；新表结构在 `V12__platform_console.sql`（H2 MySQL 模式与 MySQL 8.4 都跑过）。

**新接口**（完整状态码见 `docs/phase-0-contract.md` 的「HTTP · Core（Phase 5 新增）」）

| 接口 | 鉴权 | 说明 |
|---|---|---|
| `POST /api/v1/admin/login` | 无（按 IP 限流） | `{username,password}` → `token`（`vadm1.` + HS256 JWT）、`expires_at`；`no-store` |
| `GET /api/v1/admin/me` | 管理 JWT | 当前管理员 |
| `GET/POST /api/v1/admin/tenants`，`GET/PATCH /api/v1/admin/tenants/{id}` | 管理 JWT | 租户列表/创建/详情；PATCH 改 `name`、`status`（ACTIVE/DISABLED）、`storage_quota_bytes`、`max_concurrent_jobs`、`max_daily_transcodes`（`null` 回到默认，0 不限） |
| `GET/POST /api/v1/admin/tenants/{id}/keys` | 管理 JWT | 列 key（不含明文与哈希）/ 新建（`name`、`scopes`、`expires_at` 或 `ttl_seconds`），明文 `api_key` 只在 201 响应里出现一次 |
| `POST /api/v1/admin/keys/{id}/revoke`，`POST /api/v1/admin/keys/{id}/rotate` | 管理 JWT | 撤销；轮换（可选 `grace_seconds`，0 = 旧 key 立即撤销），新明文只出现一次 |
| `GET /api/v1/admin/tenants/{id}/usage?days=` | 管理 JWT | 指定租户的用量与配额 |
| `GET /api/v1/admin/templates?tenant_id=`，`POST /api/v1/admin/tenants/{id}/templates`，`PUT /api/v1/admin/templates/{id}` | 管理 JWT | 模板；系统模板默认只读 |
| `GET /api/v1/admin/webhook-deliveries?tenant_id=&endpoint_id=&job_id=` | 管理 JWT | 投递记录（不含签名密钥） |
| `POST /api/v1/assets/{id}/transcodes` | API Key，scope `asset` | 按模板转码/重新转码，`Idempotency-Key`；202 / 重放 200 / 进行中 409 |
| `POST /api/v1/jobs/{id}/retry` | API Key，scope `job` | 重试 FAILED（默认含 CANCELLED），`Idempotency-Key`；202 / 409 / 跨租户 404 |
| `GET /api/v1/templates` | API Key，scope `asset` | 系统模板 + 本租户模板 |
| `POST /api/v1/webhooks/{id}/disable`、`/enable`，`GET /api/v1/webhook-deliveries?endpoint_id=&job_id=&limit=` | API Key | 停用/启用（启用前重新做 SSRF 检查）、投递记录 |
| `GET /api/v1/usage?days=` | API Key | 原有今日字段 + `quota`、`storage_used_bytes`、`concurrency`、`transcodes_today`、`history` |

**各项行为变化**

1. **管理端鉴权**：加了 `spring-boot-starter-security`，但 `SecurityFilterChain` 只匹配 `/api/v1/admin/**`（`vod.admin.AdminSecurityConfig`），其他路径不进安全过滤链，响应头和放行规则都和以前一样（Spring Security 的防火墙仍会拒绝非规范化路径）。`ApiKeyFilter` 只做了三处小改：跳过 `/api/v1/admin/**`、连带检查租户状态、按租户限流。JWT 是 `vadm1.` 前缀加 HS256（只认 HS256，拒绝 `alg=none`，校验 `aud`/`iss`/`exp`），密钥 `VOD_ADMIN_JWT_SECRET` 走 `SecretGuard`（非 dev 下为空、等于 `dev` 或过短就拒绝启动）。每个请求都会查一次管理用户是否仍 `ACTIVE`，停用后旧 token 立即失效。口令只存 BCrypt；`VOD_ADMIN_USERNAME`/`VOD_ADMIN_PASSWORD` 种子只在用户不存在时建号，仓库和镜像里没有默认口令。不做 RBAC/OAuth。
2. **租户与 Key**：key 形如 `vk_…`，库里存 SHA-256 和前 10 位 `key_prefix` 用于辨认。scopes 只能是 `upload`/`asset`/`job`/`*`。停用租户后该租户所有 key 都 401，重新启用后恢复。`DevCredentialSeeder` 与 `dev-demo-key` 保留。
3. **Webhook 事件与可靠投递**：事件名和 payload 见契约。`transcode.requested` 不再在 `afterCommit` 里同步发 HTTP；所有事件都写进 `vod_webhook_event`，不进 `vod_outbox`。成功、最终失败事件由 Worker 在 `fencing.commit_success` / `apply_failure` 的同一事务里插入；取消事件在 vod-api 的取消事务里插入。`WebhookDispatcher` 照 `OutboxPublisher` 的做法：条件 UPDATE 领取，可多实例。分发时把 payload 固定下来，每个端点一条投递；投递时 2xx 记 `DELIVERED`，否则指数退避加 jitter，满次数后 `FAILED`，端点停用记 `SKIPPED`。事务里不发 HTTP。投递记录查询从不返回 secret。
4. **SSRF / DNS 重绑定**：`WebhookUrlGuard` 补了 IPv6（只收 `2000::/3`，拒绝回环、链路本地、ULA、文档/Teredo/6to4，IPv4 映射与 NAT64 按内嵌 IPv4 判断）以及 CGNAT/保留段。登记和每次投递都走 `WebhookTargetPolicy.resolve`（解析一次 → 全部地址公网 → 返回地址）。`LiveWebhookIo` 用 socket 连到这个 IP，TLS 用原主机名做 SNI 和证书主机名校验，`Host` 头也是原主机名，不跟随重定向，响应只读有限字节。dev 开关 `VOD_WEBHOOK_DEV_ALLOW_HTTP`、`VOD_WEBHOOK_DEV_ALLOW_PRIVATE_HOSTS` 默认关，只在 `VOD_ENV=dev` 生效，非 dev 下设置会拒绝启动；即使在放行名单里，链路本地和 metadata 地址仍会被拒。
5. **模板与重新转码**：`vod_transcode_template`（`tenant_id=0` 是系统模板；默认 `ladder_v1` = 720/2500、540/1200、360/600、AAC 96k，与原梯队一致）。模板名就是任务的 profile，所以 `(asset, profile)` 仍唯一，租户模板不能和系统模板重名。重新转码在同一事务里依次：锁租户行（配额）→ Idempotency-Key → `SELECT 任务 FOR UPDATE` → 终态才重置（`PENDING`、`claim_version+1`、清 attempts/错误/租约，写 `template_id`）→ 非 READY 资产改回 `UPLOADED` → 新 outbox（消息 v1）→ `transcode.requested` 事件 → 记一次转码用量。Worker 按 `job_id` 读模板（`worker/transcode_template.py`），领取时再 +1 版本并按 Phase 4 插入新的 `WRITING` 前缀。**Worker 改动**：Core 资产已经 `READY` 时，`TRANSCODING`/`FAILED` 不再降级它，旧前缀在新前缀成功切换之前继续可播（旧 Demo 的 SQL 不变）。加密资产沿用已有的 `vod_content_key`（`INSERT IGNORE`）。新增不可重试错误码 `TEMPLATE_INVALID`。
6. **重试**：与 `dlq.py replay` 同一套重置规则，以数据库为准；`vod.jobs.retry-allow-cancelled` 默认 `true`。DLQ 里的旧消息不会被清理，它对应的任务已不是 FAILED，是无害残留。
7. **限流与配额**：令牌桶。配了 `REDIS_URL`（compose 已给 vod-api 加上）就用 Redis + Lua，多实例共享额度；Redis 连不上时退回进程内存桶，只打限频日志、不拒绝请求。**没有 Redis 时的内存桶只对单实例有效**。租户 API 按租户（不按 key）限流，管理登录按客户端 IP 限流（读 Nginx 写的 `X-Real-IP`；vod-api 直接对外时请把 `VOD_RATELIMIT_CLIENT_IP_HEADER` 设为空），出站 Webhook 按租户限速（被限速只推迟，不计次数）。转码配额在 complete、重新转码、重试时锁租户行检查，超额 **429 `quota_exceeded`**（存储配额仍是 403）。并发数指未结束（PENDING/RETRYING/RUNNING）的任务，每日次数沿用 `vod_usage_daily.transcode_jobs`（complete、重转、重试各计一次）。代价是同一租户建任务的事务会串行。
8. **用量**：见上表；`?days=` 上限 `vod.usage.max-history-days`，超过时截到上限。播放计数 GETDEL 问题没做。
9. **控制台**：`console/` 仍是 Vue 3 + TS + Vite，只加了 `vue-router`（hash 路由，Nginx 不需要回退规则），没有引入 UI 框架。页面有：管理登录、租户（创建、启停、配额）、租户详情（Key 新建/轮换/撤销，明文只显示一次，可选择「在本标签页使用」；用量、租户模板、投递记录）、资产（列表、直传上传、详情、申请播放 token 用 hls.js 试播，加密 key 请求带 Bearer）、任务（取消/重试/按模板重转、该任务的投递）、模板、Webhook（登记、停用/启用、投递记录）、用量与配额。管理接口用 JWT，租户接口用选用的租户 Key，都只存在本标签页的 sessionStorage。构建产物已提交到 `nginx/html/vod-console/`。旧播放页和 `console.html` 没改。开发时 `VOD_CONSOLE_PROXY=http://localhost:8001 npm run dev` 可以直连本机 vod-api（默认仍代理到 8080）。
10. **验收脚本**：`scripts/platform_live.py` 配合 `docker-compose.platform-live.yml`（只打开上面两个 dev 开关，并给 vod-api 加 `host.docker.internal`）。

**配置项**（默认值只是开发默认，不是基准或承诺；Spring 数值项在 compose 里没透传，需要时用 override 给 vod-api 设环境变量）

| 名称（环境变量） | 默认 | 含义 |
|---|---|---|
| `VOD_ADMIN_JWT_SECRET` | 空（dev 下每次启动随机生成） | 管理 JWT 的 HMAC 密钥，非 dev 必填 |
| `VOD_ADMIN_JWT_TTL_SECONDS` / `VOD_ADMIN_JWT_LEEWAY_SECONDS` | 3600 / 30 | token 有效期 / 时钟偏差容忍 |
| `VOD_ADMIN_USERNAME` / `VOD_ADMIN_PASSWORD` | 空 | 开发用管理员种子（都设置时才建号；非 dev 下口令短于最小长度不建） |
| `VOD_ADMIN_MAX_KEY_TTL_SECONDS` | 0 | 新 key 的最长有效期，0 = 允许不过期 |
| `VOD_RATELIMIT_ENABLED` / `VOD_RATELIMIT_REDIS_URL`（缺省取 `REDIS_URL`）/ `VOD_RATELIMIT_REDIS_TIMEOUT_MS` | true / 空 / 200 | 开关、Redis 地址、Redis 超时 |
| `VOD_RATELIMIT_TENANT_RPS` / `_BURST` | 50 / 200 | 租户 API |
| `VOD_RATELIMIT_LOGIN_RPS` / `_BURST` | 0.2 / 10 | 管理登录（每 IP） |
| `VOD_RATELIMIT_WEBHOOK_RPS` / `_BURST` | 5 / 20 | 出站 Webhook（每租户） |
| `VOD_RATELIMIT_CLIENT_IP_HEADER` | `X-Real-IP` | 登录限流取 IP 的头，直接对外时设为空 |
| `VOD_QUOTA_DEFAULT_MAX_CONCURRENT_JOBS` / `VOD_QUOTA_DEFAULT_MAX_DAILY_TRANSCODES` | 0 / 0 | 租户列为 NULL 时的上限，0 = 不限 |
| `VOD_USAGE_MAX_HISTORY_DAYS` | 90 | `?days=` 上限 |
| `VOD_IDEMPOTENCY_TTL_SECONDS` | 86400 | 重转/重试 Idempotency-Key 的有效期 |
| `VOD_JOBS_RETRY_ALLOW_CANCELLED` | true | 是否允许重试 CANCELLED |
| `VOD_TEMPLATE_ALLOW_SYSTEM_EDIT` | false | 系统模板是否可改 |
| `VOD_WEBHOOK_DISPATCH_INTERVAL_MS` / `_BATCH_SIZE` / `_CLAIM_SECONDS` | 2000 / 20 / 120 | 投递循环 |
| `VOD_WEBHOOK_MAX_ATTEMPTS` / `_BASE_BACKOFF_SECONDS` / `_MAX_BACKOFF_SECONDS` / `_BACKOFF_JITTER` | 8 / 10 / 3600 / 0.5 | 重试次数与退避 |
| `VOD_WEBHOOK_CONNECT_TIMEOUT_MS` / `_READ_TIMEOUT_MS` / `_MAX_RESPONSE_BYTES` | 3000 / 5000 / 4096 | 投递超时与响应体上限 |
| `VOD_WEBHOOK_DEV_ALLOW_HTTP` / `VOD_WEBHOOK_DEV_ALLOW_PRIVATE_HOSTS` | false / 空 | 仅 dev 的回调放行开关 |

**事件名**：`transcode.requested`、`transcode.succeeded`、`transcode.failed`、`transcode.cancelled`（取消不复用 failed）。

**新指标**（Micrometer，不带 URL、租户、IP 或密钥标签）：`vod_webhook_deliveries_total{result=delivered|retry|failed|blocked|skipped|rate_limited}`、`vod_ratelimit_rejected_total{scope=tenant|login|webhook}`、`vod_admin_logins_total{result=ok|denied}`。

**本分支实际跑过的测试**（2026-11-11，macOS，本机**没有 Docker**）

- `cd vod-api && mvn test`：111 个用例（原 82 + 新增 29）。未设 `VOD_TEST_MYSQL_URL` 时 0 失败、7 个 MySQL 用例跳过；设到本机 MySQL 8.4.10 再跑，111 个全过、0 跳过。这一轮 Flyway V1–V12 在 MySQL 上执行；`Phase5MysqlTest` 在真 MySQL 上验证了租户行锁下两个并发请求只有一个能过并发配额，也覆盖了事件、投递、幂等和用量历史。真 MySQL 抓到过一个 H2 测不出的问题：TIMESTAMP 会把毫秒四舍五入到秒，新投递写 `next_attempt_at=now` 可能落在查询时刻之后。已改成留空，表示立即到期。
- 新增 Java 测试：
  - `AdminApiTest`、`AdminJwtTest`：隔离；伪造、过期、`alg=none` 的 token；Key 生命周期；停用租户。
  - `TranscodeApiTest`：重转、409、幂等、重试、并发与每日 429、用量。
  - `WebhookDeliveryTest`：事件不进 outbox、签名、退避与最终失败、停用；**DNS 先返回公网、后改成 127.0.0.1 时不发起连接**；实际连接的正是刚检查过的地址。
  - `WebhookSsrfGuardTest`。
  - `PinnedPosterTest`：真实 socket。主机名解析不了时，请求仍到达钉住的 IP，`Host` 头是原主机名；HTTPS 用 keytool 生成的自签证书，主机名对得上握手成功，对不上握手失败。
  - `RateLimitTest`、`Phase5MysqlTest`。
- 改了的已有测试：`SecretGuardTest` 的 prod 上下文用例补了 `vod.admin.jwt-secret`（非 dev 现在要求这把密钥）；`test_worker_reliability.py` 对 Core 的 `TRANSCODING` SQL 期望多了 `AND status<>'READY'`（旧 Demo 期望不变）；`FakeWebhookIo` 换成新接口，默认解析到公网地址。
- Python 3.12：以下全过：`contract_check`、`test_bridge`、`test_hls_verify`、`test_vod_client`、`test_worker_fencing`（7）、`test_worker_reliability`（39）、`test_dlq`（5）、`test_output_gc`（9）、`test_playback_contract`、`test_api_playback`（8）、新增 `test_transcode_template`（3）；`nginx_local_check.py` 用本机 Homebrew nginx 跑，33/33。
- `test_fencing_mysql.py` 连本机 MySQL 8.4.10：28 个全过。新增的 4 个覆盖：重转复用同一行并切换前缀；重转失败时 READY 不降级，只有最终失败才记 failed 事件；围栏丢失不留事件；Worker 从库里读模板。
- 本机冒烟（不是 compose 栈）：
  - 环境：打包后的 jar 连本机 MySQL 8.4 空库（Flyway V1–V12）；`REDIS_URL` 指向不存在的端口，用来验证退回内存桶；打开 dev 开关，在本机起 HTTP 接收端；用真实的 `fencing.py` 模拟 Worker 成功。
  - 29 项检查全过：登录、隔离、SSRF 拒绝；真实 HTTP 投递 requested/succeeded/cancelled，签名正确，`Host` 是原主机名；重转复用行且资产保持 READY；重试、用量、每日配额 429、停用租户、登录按 IP 429；`vod_outbox` 里没有 Webhook 行。
  - 这次冒烟发现 `LiveWebhookIo` 有两个构造器，Spring 选不出来导致启动失败（单测用的是假实现，测不到），已修。
  - Vite dev 服务能起，代理到 vod-api 正常。**本机没有可用的浏览器，控制台页面没有做视觉或交互验证**，只过了 `vue-tsc` 类型检查和生产构建。
- **没跑**：本机没有 Docker，以下都没验证：`docker compose` 起栈、`scripts/platform_live.py`、`scripts/e2e.py`、`scripts/playback_live.py`、真实 Worker + FFmpeg 按模板转码、真 Redis 上的 Lua 限流（只验证了 Redis 不可达时的回退）。

```bash
# 需要 Docker：独立项目名；端口冲突时在 .env 改 HTTP_PORT/UPLOAD_PORT 或加 override
VOD_ADMIN_USERNAME=ops VOD_ADMIN_PASSWORD='<自己生成>' \
  docker compose -p drama-vod-p5 -f docker-compose.yml -f docker-compose.platform-live.yml up -d --build
COMPOSE_PROJECT_NAME=drama-vod-p5 VOD_ADMIN_USERNAME=ops VOD_ADMIN_PASSWORD='<同上>' python3 scripts/platform_live.py
python3 scripts/e2e.py
```

**剩余风险**

- compose 栈下的整条链路都没在本机验证过：真实上传 → Worker 按模板转码 → 回调 → 重转切换前缀 → GC，以及 `platform_live.py` 本身。脚本只做了语法检查。
- 每个请求都查一次管理用户状态；JWT 不能单独吊销（只能停用用户或换密钥）。
- 内存限流桶只对单实例有效。Redis 不可达时 fail-open，也就是退回各实例自己的额度。
- 转码配额靠锁租户行串行化，同一租户高并发建任务时会排队。并发上限按「未结束任务」计，不是只算 RUNNING。
- Webhook 是至少一次投递，接收方要按 `X-Vod-Event-Id` 去重。`vod_webhook_event` / `vod_webhook_delivery` 没有保留期清理。投递每轮在单线程里顺序发，慢端点会拖慢同实例的其他投递（每次受超时上限约束）。只连解析结果的第一个地址，不轮换。
- 签名只覆盖 body，不带时间戳，没有防重放窗口（沿用 Phase 2 格式）。Webhook secret 仍是明文入库（按计划不做加密）。
- 同一资产多个模板（profile）各自成功后，播放版本是「最后一次成功」的那个；资产上没有单独的「当前模板」字段。
- 重新转码期间 READY 资产保持 READY，所以资产状态看不出正在重转，要看任务状态。
- Idempotency-Key 只对重转/重试生效；业务失败（409/429）会连同 key 一起回滚，同一 key 之后可以重新尝试。
- 控制台没在浏览器里实际点过；hls.js 来自 CDN（与播放页相同）。

## 分支方案：Phase 4 播放与分发

本分支：`plan/phase-4`，基线 `main` = `05ce971`。下面 1–10 项已在本分支实现（可选小项未做），实现后的接口、配置、指标、测试结果和剩余风险见下一节「Phase 4 实现说明」。

协作方式：其他 agent 请在**本分支**上按下面清单做最小改动开发；不要自行合并到 `main`，也不要另开平行分支。开发完后由「短剧视频服务器开发bot」检查，没问题再合并。

对照文档：[路线图](docs/panda-vod-roadmap.html) 第 07 节「临时输出目录 + 完成后原子切换」、第 08 节（短期 token / 签名 URL、HLS Key 不明文暴露、KeyProvider、Key API 鉴权与限流、敏感响应禁止公共缓存）、第 09 节 Phase 4（「MinIO 私有桶、播放 token / 签名 URL、Nginx 回源鉴权、HLS playlist 与分片访问、缓存和 CORS；补充首帧时间、播放失败、卡顿等 QoE 指标」）、第 10 节第 5、6、7 条验收。目标：**Core 资产能被安全地播放**（加密的也能），播放器永远看不到写了一半的产物，过期或伪造的 token 拿不到内容，播放质量看得见。

### 已经完成，不要重做

- HLS 桶私有：启动时删桶策略（`api/main.py:104-108`）；Nginx 验签后带 `X-Origin-Secret` 回源 `/internal/origin/{scope}/{asset}/{file}`（`api/main.py:69-95`），对象键防路径穿越（`api/origin_path.py`）；MinIO 9000 不对外暴露，上传网关只放行 PUT（`default.conf.template:96-113`）。
- `secure_link` 签名 URL：旧 Demo `/hls/...` 与 Core `/hls/v1/...`，签名错 403、过期 410；缓存键不含签名、`proxy_cache_lock`、`use_stale`（`default.conf.template:53-90`）。Java 侧 `HlsSigner.v1MasterUrl`（`HlsSigner.java:17-23`）。
- `GET /api/v1/assets/{id}/playback`：scope `asset`、租户可见性、只给 `READY` 签地址、记一次播放用量（`PlaybackController.java:34-50`）。
- 旧 Demo：`/keys/{asset_id}` 凭 `TOKEN_SECRET` token 返回 16 字节、`no-store`，Nginx 不缓存（`api/main.py:507-528`，`default.conf.template:41-49`）；iPhone 原生 HLS 改写列表（`api/main.py:586-603`）；首片约 1 秒（`worker.py:54`）；下一集预加载（`api/main.py:487-500`，`index.html:755-762`）。
- 本地 HLS 校验后才上传、`master.m3u8` 最后传、上传前复核围栏，`READY` 与 `hls_prefix` 在带 `claim_version` 的成功事务里一起写（`worker.py:280-293`，`fencing.py:85-111`）；加密 key 文件写在工作目录、不在 `out/`，不会传进 HLS 桶（`worker.py:273-287`）。
- Prometheus 抓 api / vod-api / worker（DNS 发现）/ rabbitmq（Phase 3）。

### 本分支要做的

建议按顺序做。新表结构用 Flyway **V10 及之后**，不要改 V1–V9。旧 Demo 链路（`/hls/{sig}/{exp}/{asset}/...`、`/keys/{asset_id}`、`TOKEN_SECRET` token、`assets/{id}/` 前缀、缓存键 `{asset}/{file}`、`/play` 字段）按 `docs/phase-0-contract.md` 冻结，下面的改动只作用于 Core。消息 v1 契约不改（所有新信息都能从 `job_id` + 库里的 `claim_version` 推出来）。

1. **Core 播放 token：`POST /api/v1/assets/{id}/playback-token`**
   现状：Core 只有 `GET .../playback` 返回签名 `master_url`，没有 token（`PlaybackController.java:34-50`）；`PlayToken.java` 是旧 Demo 格式（`TOKEN_SECRET`，只绑 `u`/`a`/`exp`），只在 `CompatVectorsTest` 里用；路线图第 06 节的接口不存在。
   要改：新增接口（scope 沿用 `asset`，跨租户/不存在走现有不可见处理，非 `READY` 409）。签发 Core 专用 token：绑定 `tenant_id`、`asset_id`、`exp`（可选不透明 `viewer_id`，只用于日志/QoE，不入库），HMAC 用新密钥 `VOD_PLAY_TOKEN_SECRET`，格式带版本前缀与旧 token 区分；旧 `/keys/` 不接受 Core token，Core key 接口也不接受旧 token。响应 `token`、`master_url`（与第 3 项签名路径一致）、`expires_at`、`encrypted`，带 `Cache-Control: no-store`。可选 body `ttl_seconds`，上限走配置 `vod.playback.token-ttl-max-seconds`。现有 `GET .../playback` 保留（Demo 在用），签名逻辑与新接口共用。
   验收：Java 测试覆盖正常签发、跨租户 404、scope 不足 403、非 READY 409、伪造签名/改 asset/过期 token 校验失败、旧 token 与 Core token 不互通。

2. **加密 Core 资产的 key 链路**
   现状：Core 加密资产与旧 Demo 共用 `content_key` 表、同一个 `asset_id` 主键（`worker.py:170-174, 273-279`，`mysql/init.sql:81-86`）。`vod_media_asset.id` 与 `video_asset.id` 是两套自增，**会撞号**：Core 资产 5 可能拿到旧资产 5 的 key；Key URI 写的是旧的 `/keys/{asset_id}`，需要旧 token，Core 播放器拿不到，加密 Core 资产实际不可播（见 `vod-review-cf644f7` 报告）。
   要改：V10 新建 `vod_content_key(asset_id PK → vod_media_asset, key_hex, iv_hex, created_at)`；Worker 对 `origin=core` 读写这张表、Key URI 写 `/keys/v1/{asset_id}`，旧 Demo 不变。vod-api 新增 `GET /keys/v1/{asset_id}`（不在 `/api/v1` 下，不走 `ApiKeyFilter`），接受 `Authorization: Bearer` 和 `?token=`：token 有效、`asset_id` 一致、资产属于 token 的租户、`encrypted=1` 且 `READY` 才返回 16 字节，`Cache-Control: no-store, private`；否则 401（无 key 404），与旧接口语义对齐。Nginx 加 `location /keys/v1/` 到 vod-api，不缓存，`limit_req` 限流（速率、突发写成配置）。按第 08 节抽象 `KeyProvider`（Java 读、Python 写各一处），本分支只实现开发用明文存储，不做 KMS。
   验收：Worker 单测覆盖 Core 用 `vod_content_key`、URI 是 `/keys/v1/{id}`、旧 Demo 仍用 `content_key` 和 `/keys/{id}`；Java 测试覆盖 key 接口 200/401/404、跨租户 token、未加密资产、响应头。已经 READY 的旧 Core 加密资产（URI 是 `/keys/{id}`）不迁移，写进风险。

3. **任务专属输出前缀 + 原子切换**
   现状：Core 产物固定写 `assets/v1/{asset_id}/`（`job_route.py:17-21`），新旧 Worker 会往同一前缀写，复核之前已上传的分片可能被覆盖（Phase 3 剩余风险）；签名只覆盖资产 id（`HlsSigner.java:17-23`），缓存键 `v1/$asset/$file` 缓存 7 天（`default.conf.template:80-81`），同一路径一旦内容变化就会命中旧缓存。`hls_prefix` 已写库但回源路径不用它（`api/origin_path.py:13`）。
   要改：Core 每次 claim 写到 `assets/v1/{asset_id}/{gen}/`，`gen = {job_id}-{claim_version}`（只含数字和 `-`）。上传完、提交前按本地文件清单对远端做一次 list/HEAD 校验（名字和大小一致）。现有成功事务（围栏 + `READY` + `hls_prefix`）就是原子切换点，不另起机制。播放侧按 `hls_prefix` 生成 `/hls/v1/{sig}/{exp}/{asset}/{gen}/master.m3u8`，签名明文 `{exp}/v1/{asset}/{gen} {HLS_SECRET}`；`hls_prefix` 仍是旧固定前缀的资产继续用现有 URL。Nginx 新增带 `gen` 的 location，**放在现有 `/hls/v1/` 正则之前**（正则 location 按顺序匹配），缓存键 `v1/$asset/$gen/$file`；回源可复用 `/internal/origin/v1/{asset}/{gen}/{file}`（`object_key` 已允许多级路径），`origin_path.py` 只需按需加 `gen` 格式校验。旧 Demo 前缀与路由不动。
   验收：单测覆盖前缀格式、远端校验缺文件/大小不符时不提交、签名向量（Java 与 Nginx 一致，写进 `CompatVectorsTest` 或新测试）、旧前缀资产 URL 不变、伪造 `gen` 403；compose 下同一资产两次 claim 产生两个前缀，播放地址只指向提交成功的那一个。

4. **旧前缀清理**
   现状：没有任何产物清理；取消发生在 master 已传、事务未提交时会留下完整产物（Phase 3 剩余风险），失败重试的半成品也一直留着。
   要改：V10（或 V11）新建 `vod_output_prefix(asset_id, job_id, claim_version, prefix UNIQUE, status WRITING|ACTIVE|GARBAGE|DELETED, created_at, updated_at)`。Worker 上传前插 `WRITING`；成功事务里把自己置 `ACTIVE`、同资产其他行置 `GARBAGE`；最终失败、取消（Java 取消事务里一起改）、围栏丢失时把对应行置 `GARBAGE`；`WRITING` 且版本已落后于任务当前 `claim_version` 的也视为垃圾。Worker 的 sweeper 删除 `GARBAGE` 且超过宽限期的前缀（分页 list + 批量删），删完置 `DELETED`；**绝不删等于资产当前 `hls_prefix` 的前缀**（删前再查一次）。宽限期 `WORKER_OUTPUT_GC_GRACE_SECONDS` 须大于租约与播放 URL 有效期，开关和批大小也走环境变量。Phase 4 之前的固定前缀和旧 Demo 前缀不碰。
   验收：单测覆盖状态流转、宽限期内不删、不删 ACTIVE / 当前 `hls_prefix`、取消后的完整产物会被清理；`fault_inject.py` 新增场景（双 Worker 同任务 → 只有一个 ACTIVE，垃圾前缀在缩短的宽限期后被删）。

5. **缓存、CORS 与 Range**
   现状：所有 HLS 响应 `proxy_cache_valid 200 7d` 且 `proxy_ignore_headers Cache-Control`（`default.conf.template:59-64, 79-84`），客户端拿到的响应没有显式 `Cache-Control`；`/hls/` 没有 CORS，跨域播放器（另一个域名的 hls.js）拉不到；403/410 响应没有 `no-store`。
   要改：只改 Core 路由（旧 `/hls/` 冻结）：带 `gen` 的路径内容不可变，给客户端 `Cache-Control: public, max-age=<配置>, immutable`；旧固定前缀的 Core 路径 m3u8 用较短 TTL（配置），分片保持长 TTL；403/410、key、playback-token、QoE 响应一律 `no-store`。`/hls/v1/` 与 `/keys/v1/` 加 CORS（`GET, OPTIONS`；key 接口允许 `Authorization` 头），允许的 Origin 走配置（Nginx envsubst），开发默认写清楚。确认缓存命中时 `Range` 请求返回 206，不需要改回源。TTL 只是配置，不是基准。
   验收：compose 下 curl 截取：gen 路径 MISS→HIT、响应头、预检 OPTIONS、Range 206、403/410 带 `no-store`；没跑写明未验证。

6. **回源与签名密钥加固**
   现状：`ORIGIN_SECRET` 在 compose 默认 `dev`（`docker-compose.yml:72, 122`），`.env` 里也没有这一项；`api/main.py:31-35` 的 `HLS_SECRET`/`TOKEN_SECRET`/`ORIGIN_SECRET` 默认 `dev`，`vod.hls-secret` 默认 `dev`（`application.yml:14`）；回源用 `!=` 比较（`api/main.py:73`）。
   要改：新增 `VOD_ENV`（compose 传 `${VOD_ENV:-dev}`）。非 `dev` 时 `ORIGIN_SECRET`、`HLS_SECRET`、`TOKEN_SECRET`、`VOD_PLAY_TOKEN_SECRET` 为空、等于 `dev` 或短于配置的最小长度就**拒绝启动**（FastAPI startup、Spring 启动校验、Nginx 用 `/docker-entrypoint.d/` 脚本）；`dev` 下只打警告。回源密钥改常量时间比较（`hmac.compare_digest`）。compose 去掉 `:-dev` 兜底的方式要保证 `dev` 下仍能一条命令起来。README 写明用 `openssl rand -hex 32` 生成并放进 `.env`，仓库里不出现任何真实值。
   验收：Python 与 Java 测试覆盖非 dev 缺密钥/弱密钥启动失败、dev 下可启动；`/internal/origin` 错误密钥 403。

7. **QoE 指标**
   现状：播放页只在本地记 `window.vodState.firstFrameMs`、错误列表（`index.html:370, 917-924, 764-767`），不上报；服务端没有任何播放质量指标。
   要改：vod-api 新增 `POST /api/v1/qoe`（`ApiKeyFilter` 对这一路径放行，用 body 里的 Core 播放 token 鉴权，因为 `sendBeacon` 不能带头；token 过期后允许一个可配置的宽限期，播放时长常超过 token 有效期）。body：客户端随机 `session_id` + 一批事件（`startup` 首帧毫秒、`rebuffer` 卡顿开始/时长、`error` 错误码、`ended`），租户与资产只从 token 取，不信客户端；限制批大小与 body 大小。记 Micrometer：`vod_qoe_startup_seconds`（histogram）、`vod_qoe_rebuffer_total`、`vod_qoe_rebuffer_seconds`、`vod_qoe_playback_errors_total{code}`（错误码白名单，未知归 `other`，标签不带 `asset_id`/`session_id`），返回 204。Nginx 对该路径 `limit_req`。播放页在拿到 Core token 时上报（首帧、`waiting` 到 `playing` 的卡顿、致命错误，`pagehide` 时 `sendBeacon` 冲刷）。不建表、不写阈值或 SLO，不编造数字。
   验收：Java 测试覆盖无效 token 401、超大 body 413、未知错误码归类、指标出现在 `/actuator/prometheus`；README 写明指标名。

8. **Demo 桥接走新接口**
   现状：`vod_client.call` 只捕获 `HTTPError`，Core 宕机时 `URLError`/超时变成 500（`api/vod_client.py:8-25`）；`vod-play` 固定返回 `encrypted: False`、`token: ""`（`api/main.py:460-475`）。
   要改：`vod_client` 把连接错误、超时转成 502 语义的返回；新增 `playback_token()`，`vod-play` 改调 `POST .../playback-token`，返回 Core token 和真实 `encrypted`，付费解锁仍由 Demo 的 `can_play` 决定。播放页 `xhrSetup` 已对 URL 含 `/keys/` 的请求加 Bearer 头（`index.html:742`），`/keys/v1/` 自然适用。旧 `/play` 不动。
   验收：`test_vod_client.py` / `test_bridge.py` 覆盖 URLError → 502、vod-play 返回 token。

9. **（Phase 3 遗留）取消与 Worker 加锁顺序统一**
   现状：取消事务先锁任务行再改资产（`JobService.java:26-46`）；Worker 写 `TRANSCODING` 是自动提交的一条 `UPDATE 资产 JOIN 任务`（`worker.py:228`，`fencing.py:15-24`，连接 `autocommit=True`），MySQL 可能先锁资产再锁任务，两者撞上可能死锁，取消请求被选为牺牲者时抛 `DeadlockLoserDataAccessException`，现在会变成 500。成功事务和失败事务本来就先写任务行，顺序一致。
   要改：Worker 写 `TRANSCODING` 改为显式事务，先 `SELECT ... FROM vod_transcode_job WHERE id=? FOR UPDATE` 锁任务行再改资产；取消接口对死锁/锁超时异常整体重试一次（在 `@Transactional` 外层重试），仍失败返回 503 而不是 500。
   验收：Worker 单测检查语句顺序；Java 测试注入一次死锁异常后重试成功；有 MySQL 时用两个连接交错复现（`VOD_TEST_MYSQL_URL` 下运行，否则跳过）。

10. **验收脚本与文档**
    新增 `scripts/playback_live.py`（compose 下 Core 链路）：上传 → READY → playback-token → 经 Nginx 拉 master/子列表/分片；改签名 403、过期 410、伪造或过期 token 取 key 401、跨租户 404、容器内匿名直读 MinIO HLS 对象被拒；加密资产能取到 16 字节 key 并用 ffmpeg 解出一帧。更新 `docs/phase-0-contract.md`「播放签名」「对象与 HLS」两节：补 Core 的 `gen` 路径、签名明文、`/keys/v1/`、Core token，旧 Demo 条目保持冻结原文。README 补新接口、配置项、指标名。

**可选小项（不做不影响验收，做了单独说明）**：Worker 在 FFmpeg 轮询里每 `WORKER_CANCEL_CHECK_SECONDS` 轻量查一次任务状态和 `claim_version`，让取消不必等下一次心跳（现在最长约一个心跳间隔，见下方 Phase 3 说明）。

### 本分支不要做

- 旧 Demo 的签名算法、`/hls/` 路由与缓存键、`/keys/{asset_id}`、`TOKEN_SECRET` token 格式、`assets/{id}/` 前缀、`content_key` 表（Phase 0 冻结）；也不要迁移旧 `content_key` 或已 READY 的旧 Core 加密资产。
- Demo 付费集改走加密 Core 资产，以及 Core 加密资产的 iPhone 原生 HLS 改写列表（类似 `/api/playback/...`）：要同时改上传桥接和原生播放，留到下一阶段，写进风险。
- KMS / 信封加密的真实实现（只抽象 `KeyProvider`）、DRM（Widevine/FairPlay）。
- 回源密钥轮换、mTLS、来源 IP 限制；CDN 刷新/预热接口；Nginx exporter、Grafana 仪表盘；QoE 落库、`usage_daily` 播放流量聚合。
- `vod-play` 的下一集预加载、首片时长调整（已有，不改）。
- Phase 3 遗留里的「RabbitMQ 断线时 retry/DLQ 消息可能丢」：Worker 发 retry/DLQ 没有 publisher confirms（`worker/common.py:54-57`），但库里状态正确，Core 的 sweeper 兜底 `RETRYING`，DLQ 重放以库为准，这次不改。
- Phase 5：控制台（租户、API Key、模板）、Webhook 重试与 DNS 重绑定、`transcode.succeeded/failed` 事件、`transcode_template` 与 `POST /assets/{id}/transcodes`（重新转码，所以本分支的切换只发生在首次 READY 与 DLQ 重放后）、租户侧重试接口、管理端 Spring Security + JWT、API 整体限流与配额。
- Phase 6：Multipart、缩略图、字幕/音轨、CMAF/fMP4、H.265/AV1、多对象存储、多 CDN、生命周期与冷存储。
- 不要重写 Worker 转码流水线、不要换 MQ、不要改消息 v1、不要改 Windows 或 `~/.claude/settings.json`、不要把密钥写进仓库。

### 约束

- Flyway V10 起，不改 V1–V9；SQL 同时兼容 H2 MySQL 模式（测试）和 MySQL 8.4。`mysql/init.sql` 只管旧 Demo 表，新表只进 Flyway。
- 白盒理解现有代码后最小改动；保留现有 `claim_version` 围栏、成功事务、sweeper、Outbox 语义；Java 与 Nginx 的签名算法要有共享测试向量。
- 消息 v1 不改，`api/common.py` 与 `worker/common.py` 的 `transcode_message()` 保持一致（`contract_check.py` 比对）。
- TTL、宽限期、限流速率、CORS Origin、批大小、最小密钥长度都写成配置项，默认值只是开发默认，不是基准或承诺；不要编造性能数字。

### 怎么测

- `cd vod-api && mvn test`（Java 21 + Maven）：现有 52 个用例须继续通过（未设 `VOD_TEST_MYSQL_URL` 时 4 个 MySQL 用例按设计跳过）；设置 `VOD_TEST_MYSQL_URL` 连 MySQL 8.4 再跑一遍。
- `python3 scripts/contract_check.py`、`test_bridge.py`、`test_hls_verify.py`、`test_vod_client.py`、`test_worker_fencing.py`、`test_worker_reliability.py`（3.10+）、`test_dlq.py`，以及新增的单测。
- 需要 Docker：用独立项目名起栈，例如 `docker compose -p drama-vod-p4 up -d --build`；compose 里有固定宿主端口（3306、6379、9001、15672 和 `HTTP_PORT`/`UPLOAD_PORT`），和已在跑的栈冲突时先改端口或用 override 文件，不要动已有容器和数据卷。然后跑 `test_fencing_mysql.py`、`scripts/playback_live.py`、`scripts/fault_inject.py`、`scripts/e2e.py`（旧 Demo 回归）。
- 没跑过的不要声称通过，写明原因。

## Phase 4 实现说明（已合并到 `main`，`9cab039`）

只改 Core；旧 Demo 的 `/hls/{sig}/{exp}/{asset}/`、签名算法与缓存键、`/keys/{asset_id}`、`TOKEN_SECRET` token、`assets/{id}/` 前缀、`content_key`、`/play` 字段都没动（`scripts/test_playback_contract.py` 逐字核对旧 location）。消息 v1 未改。

**新接口**

| 接口 | 鉴权 | 行为 |
|---|---|---|
| `POST /api/v1/assets/{id}/playback-token` | API Key，scope `asset` | 跨租户/不存在 404，非 `READY` 409。可选 body `ttl_seconds`（≤0 为 400，超上限截到上限）、`viewer_id`（`[A-Za-z0-9_.:-]{1,64}`，只进日志）。返回 `asset_id`、`token`（`vpt1.` 前缀，HMAC 用 `VOD_PLAY_TOKEN_SECRET`）、`master_url`、`expires_at`、`encrypted`；`Cache-Control: no-store`；记一次播放用量。 |
| `GET /api/v1/assets/{id}/playback` | 同上 | 保留（Demo 在用），与上面共用签名逻辑：`hls_prefix` 是任务专属前缀就签带 gen 的地址，否则沿用旧地址。 |
| `GET /keys/v1/{asset_id}` | Core token：`Authorization: Bearer` 或 `?token=` | 不走 API Key。token 有效、asset 一致、资产属于 token 租户、`encrypted=1` 且 `READY` 才返回 16 字节；否则 401，无 key 404；全部 `no-store, private`。Nginx 不缓存、不写访问日志（`?token=` 里有 token）、`limit_req`、CORS 允许 `Authorization`。 |
| `POST /api/v1/qoe` | body 里的 Core token（过期后有宽限） | 不走 API Key（`sendBeacon` 不能带头）。事件 `startup`/`rebuffer`（`ms`）、`error`（`code`）、`ended`；租户与资产只从 token 取。超限 413，坏 JSON 400，token 无效 401，成功 204 + `no-store`。Nginx `limit_req`、`client_max_body_size`。 |

**各项行为变化**

1. Core token：`vod.media.CorePlayToken`，格式见 `docs/phase-0-contract.md`「播放签名」的 Core 小节；旧 `PlayToken` 与它互不接受（格式和密钥都不同）。
2. Key 链路：V10 `vod_content_key`；Worker 对 `origin=core` 用这张表、Key URI 写 `/keys/v1/{id}`（`worker/key_provider.py`），旧 Demo 仍是 `content_key` 与 `/keys/{id}`；vod-api 侧 `vod.key.KeyProvider` + 开发用明文实现 `PlaintextKeyProvider`，不做 KMS。
3. 任务专属前缀：Core 写到 `assets/v1/{asset}/{job_id}-{claim_version}/`；上传完按本地清单 list 远端（名字、大小、无多余对象）不一致就按 `STORAGE_ERROR` 重试、不提交；切换点仍是带 `claim_version` 的成功事务（`hls_prefix`、`READY`、前缀 `ACTIVE` 同事务）。Nginx 新增带 gen 的 `/hls/v1/` location（排在原 `/hls/v1/` 之前），签名明文 `{exp}/v1/{asset}/{gen} {HLS_SECRET}`，缓存键 `v1/$asset/$gen/$file`；Java 与 Nginx 共用 `docs/contracts/hls-sign-vectors.json`。
4. 旧前缀清理：V11 `vod_output_prefix`（`WRITING/ACTIVE/GARBAGE/DELETED`）。上传前插 `WRITING`；成功事务自己 `ACTIVE`、同资产其他 `GARBAGE`；失败、围栏丢失（Worker）与取消（vod-api 取消事务）把 `WRITING` 置 `GARBAGE`；清理线程先把过时的 `WRITING`（版本落后、任务不在 `RUNNING`、任务不存在）改成 `GARBAGE`，再删过了宽限期的 `GARBAGE`（分页 list + 批量删），删前复核仍是 `GARBAGE`、格式是该资产的任务专属前缀、且不等于当前 `hls_prefix`，删完 `DELETED`。固定前缀与旧 Demo 前缀没有行，也过不了格式检查。另外上传循环在心跳发现围栏丢失后不再传后续文件。清理线程从不锁任务行：先用一致性读 SELECT 找过时的 `WRITING`，再按主键逐行单表 UPDATE（审阅发现最初那条 `UPDATE 前缀 JOIN 任务` 先锁前缀再锁任务，和取消/成功事务交错会在 MySQL 上死锁，已修，`test_fencing_mysql.py` 的 `GcLockOrder` 复现并覆盖）。成功事务只允许自己的前缀 `WRITING → ACTIVE`，已是 `GARBAGE` 的前缀不能被重新激活，否则整个成功事务回滚。
5. 缓存/CORS/Range：只改 Core 的两个 `/hls/v1/` location 与 `/keys/v1/`。gen 路径成功响应 `public, max-age=<配置>, immutable`；固定前缀 m3u8 短 TTL、分片长 TTL；403/410/404 等按状态码给 `no-store`；`/keys/v1/`、QoE、playback-token 都 `no-store`。CORS 白名单由 `VOD_CORS_ORIGINS` 生成 `map`，`OPTIONS` 预检 204。**原配置下缓存命中的 Range 请求实际返回 200**（回源不带 `Accept-Ranges`，Nginx 不对缓存内容做分段），所以在 Core 的两个 location 加了 `proxy_force_ranges on`，回源不变。
6. 密钥加固：`VOD_ENV`（compose 默认 `dev`）。非 dev 时空、等于 `dev`、短于 `VOD_MIN_SECRET_LENGTH` 的密钥拒绝启动：FastAPI startup 查 `ORIGIN_SECRET`/`HLS_SECRET`/`TOKEN_SECRET`，Spring `SecretGuard` 查 `HLS_SECRET`/`VOD_PLAY_TOKEN_SECRET`，Nginx 入口脚本 `nginx/entrypoint.d/05-vod-env.envsh` 查 `HLS_SECRET`/`ORIGIN_SECRET`（另拒绝会破坏 Nginx 配置的字符）；dev 只警告。compose 去掉 `ORIGIN_SECRET` 的 `:-dev`，dev 下为空时 api 与 Nginx 各自退回 `dev`。回源密钥用 `hmac.compare_digest`。
7. QoE：见上表；播放页拿到 `vpt1.` token（`vod-play`）时上报首帧、`waiting`→`playing` 卡顿（不含拖动进度条）、致命错误、结束，攒满 20 条或 `pagehide`/切到后台时 `sendBeacon`。不建表、不设阈值。
8. Demo 桥接：`vod_client` 把连接失败、超时、非 JSON 响应转成 `(502, {"detail": ...})`；新增 `playback_token()`；`vod-play` 改调新接口，返回 Core token 和真实 `encrypted`，付费解锁仍由 `can_play`（402 在调 Core 之前）。旧 `/play` 代码没动（Core 宕机时它原来会抛 500，现在 `vod_client` 返回 502，`/play` 保持旧地址）。播放页对 Core 资产不再走旧的 iPhone 改写接口。
9. 加锁顺序：Worker 写 `TRANSCODING` 改为显式事务 `SELECT … FROM 任务 WHERE id=? FOR UPDATE` → 校验 `claim_version`/`RUNNING` → `UPDATE 资产`（`fencing.mark_transcoding`，Core 与旧 Demo 都走它）；取消在 `@Transactional` 外层对 `PessimisticLockingFailureException`（死锁、锁等待超时）重试一次，仍失败 503 + `Retry-After`。
10. 新增 `scripts/playback_live.py`；`docs/phase-0-contract.md` 增补 Core 小节（旧条目原文未改）。

**配置项**（默认值只是开发默认，不是基准或承诺）

| 位置 | 名称 | 默认 | 含义 |
|---|---|---|---|
| 全部 | `VOD_ENV` | `dev` | 非 dev 时弱密钥拒绝启动 |
| | `VOD_MIN_SECRET_LENGTH` | 32 | 最小密钥长度 |
| vod-api | `VOD_PLAY_TOKEN_SECRET` | 空（dev 退回 `dev`） | Core 播放 token 的 HMAC 密钥 |
| | `vod.playback.token-ttl-seconds` / `token-ttl-max-seconds`（`VOD_PLAY_TOKEN_TTL_SECONDS` / `VOD_PLAY_TOKEN_TTL_MAX_SECONDS`） | 600 / 3600 | token 与签名地址有效期、上限 |
| | `vod.qoe.token-grace-seconds` / `max-body-bytes` / `max-events` / `error-codes` | 3600 / 16384 / 50 / hls.js 常见错误名 | QoE 宽限、body 与批大小、错误码白名单 |
| Worker | `WORKER_OUTPUT_GC_ENABLED` / `WORKER_OUTPUT_GC_GRACE_SECONDS` / `WORKER_OUTPUT_GC_INTERVAL_SECONDS` | 1 / 86400 / 300 | 清理开关、宽限期（启动时抬到不小于租约；须大于 token 最长有效期）、间隔 |
| | `WORKER_OUTPUT_GC_PREFIX_BATCH` / `WORKER_OUTPUT_GC_DELETE_BATCH` | 20 / 500 | 每轮前缀数、每批对象数（≤1000） |
| Nginx | `VOD_CORS_ORIGINS` / `VOD_CORS_MAX_AGE` | `http://localhost:8080,http://127.0.0.1:8080` / 600 | 允许的 Origin（逗号分隔或 `*`）、预检缓存 |
| | `VOD_KEY_RATE` / `VOD_KEY_BURST`，`VOD_QOE_RATE` / `VOD_QOE_BURST` / `VOD_QOE_MAX_BODY` | `20r/s` / 40，`10r/s` / 20 / `16k` | 限流与 QoE body 上限 |
| | `VOD_HLS_IMMUTABLE_MAX_AGE` / `VOD_HLS_PLAYLIST_MAX_AGE` / `VOD_HLS_SEGMENT_MAX_AGE` / `VOD_HLS_EDGE_CACHE_VALID` | 86400 / 10 / 86400 / `7d` | 客户端缓存（gen 路径、固定前缀 m3u8、固定前缀分片）、gen 路径边缘缓存 |

Spring 的数值项在 compose 里没有透传（空字符串会让 Spring 启动失败），需要时在 override 文件里给 vod-api 设环境变量。生产密钥：`openssl rand -hex 32` 生成，写进 `.env`（已在 `.gitignore`），仓库里不出现真实值。

**新指标**：vod-api `vod_qoe_startup_seconds`（histogram）、`vod_qoe_rebuffer_total`、`vod_qoe_rebuffer_seconds`（count/sum/max）、`vod_qoe_playback_errors_total{code}`（白名单外归 `other`，不带 asset/session/租户标签）、`vod_core_key_requests_total{result}`；Worker `vod_worker_output_gc_prefixes_total`。

**本分支实际跑过的测试**（2026-11-10，macOS，本机没有 Docker）

- `cd vod-api && mvn test`：82 个用例（原 52 + 新增 30），0 失败，未设 `VOD_TEST_MYSQL_URL` 时 5 个 MySQL 用例跳过；设到本机 MySQL 8.4.10 再跑：82 个全过，0 跳过（Flyway V1–V11 在 MySQL 上执行；其中 `realDeadlockIsTranslatedAndCancelRetrySucceeds` 在真 MySQL 上制造一次真实死锁，核对取消重试能接住驱动报的错）。
- Python 3.12：`contract_check`、`test_bridge`、`test_hls_verify`、`test_vod_client`、`test_worker_fencing`、`test_worker_reliability`（39）、`test_dlq`，新增 `test_output_gc`（8）、`test_api_playback`（8，需要 fastapi/httpx）、`test_playback_contract` 全过；系统 Python 3.9 跑 3.10 以下能跑的那些也全过。
- `test_fencing_mysql.py` 连本机 MySQL 8.4.10（非容器）：24 个全过，含 TRANSCODING/取消两连接交错（两种先后顺序都只等待、不死锁）、清理线程与取消/成功事务交错（修复前两条都复现 `1213 Deadlock`，修复后通过）、垃圾前缀不能再激活、前缀生命周期与清理 SQL、两张 key 表不撞号。
- 新增 `scripts/nginx_local_check.py`：用本机 Homebrew nginx（不是 compose 里的 `nginx:1.27-alpine`）渲染真实模板 + 入口脚本，对假回源跑 33 项（gen 路径 MISS→HIT、缓存键含 gen、immutable、Range 206、资产级签名读不到 gen 目录、403/410/404 no-store、固定前缀 TTL、旧 `/hls/` 和 `/keys/` 不加新头、`/keys/v1/` 预检与 429、QoE 413），全过。
- **没跑**：`docker compose` 起栈、`scripts/playback_live.py`、`scripts/fault_inject.py`（含新增 `gc` 场景）、`scripts/e2e.py`、浏览器里的 QoE 上报——本机没有 Docker，未验证；播放页 JS 只做了语法检查。

```bash
python3 scripts/test_output_gc.py
python3 scripts/test_playback_contract.py
python3 scripts/test_api_playback.py         # Python 3.10+，需要 fastapi、httpx
python3 scripts/nginx_local_check.py         # 需要本机 nginx（带 secure_link），不需要 Docker
# 需要 Docker（独立项目名，端口冲突时改 HTTP_PORT/UPLOAD_PORT 或用 override）
docker compose -p drama-vod-p4 up -d --build
COMPOSE_PROJECT_NAME=drama-vod-p4 python3 scripts/playback_live.py
COMPOSE_PROJECT_NAME=drama-vod-p4 python3 scripts/fault_inject.py --only gc
```

**剩余风险**

- compose 栈下的端到端行为（MinIO 匿名直读被拒、真实转码产物、ffmpeg 解密、Prometheus 抓 QoE、`fault_inject` 的 `gc` 场景能否抓到“正在上传”的窗口）都没在本机验证；Nginx 只在 Homebrew 版本上跑过，未在 `nginx:1.27-alpine` 镜像里跑入口脚本。
- 已经 READY 的旧 Core 加密资产 Key URI 仍是 `/keys/{id}`，需要旧 token 且查的是 `content_key`（可能撞号），未迁移，依旧不可正确播放；需要重新转码才会写成 `/keys/v1/`（重新转码接口属于 Phase 5）。
- Core 加密资产没有 iPhone 原生改写列表，iPhone 原生播放器取不到 key；Demo 付费集仍走旧链路。
- `vod_content_key` 明文存 key，只是开发用 `KeyProvider`。
- 清理依赖宽限期：已被判为垃圾的旧写入方最多在一个心跳间隔内（或 master 前复核前）还可能往自己的前缀补传文件；宽限期默认远大于此，但调得过短时可能留下孤儿对象。清理按库里的行删，`vod_output_prefix` 之外的孤儿对象不会被发现。
- `proxy_force_ranges on` 让缓存未命中时也会对回源流做单段 Range；多段 Range 只在命中时支持。
- QoE 的 `session_id`、事件内容来自客户端，只做格式与数量限制；token 宽限期内可重复上报，指标可能被同一 token 刷高（有 Nginx 限流）。
- `vod.qoe.max-body-bytes` 与 Nginx `VOD_QOE_MAX_BODY` 是两处配置，改一处要同步另一处。
- 取消重试只覆盖 `PessimisticLockingFailureException` 一类（真 MySQL 上的死锁已验证属于这一类）；别的异常不重试。

## Phase 3 转码任务可靠性（已合并到 `main`，`05ce971`）

当前任务链路的行为（细节见 `git show 05ce971:README.md`）：

- **取消**：`POST /api/v1/jobs/{id}/cancel`，scope `job`；`PENDING`/`RETRYING`/`RUNNING` → `CANCELLED`、`claim_version+1`，资产非 `READY` 时同事务改 `CANCELLED`；已取消再调 200，`SUCCEEDED`/`FAILED` 409，跨租户 404。只有 Core。在跑的 Worker 要到**下一次心跳续租失败**（最长约 `WORKER_HEARTBEAT_SECONDS`，默认 60 秒）才发现，然后在 0.5 秒轮询内杀掉 FFmpeg，不写 `READY`、不进 retry/DLQ。
- **上传会话重放**：非 `OPEN` 会话重放 200，不含 `put_url`/`expires_in`，返回 `session_id`、`asset_id`、`session_status`、`expires_at`、`job_id`、`job_status`。
- **V9**：`vod_transcode_job.error_code`、`next_attempt_at`；`vod_outbox.locked_until`、`locked_by`、`created_at`。Core 状态变化显式写 `updated_at`。
- **`error_code`**（`worker/retry_policy.py`）：不可重试 `INPUT_INVALID`、`NO_VIDEO_STREAM`、`SOURCE_MISSING`；可重试 `STORAGE_ERROR`、`DB_ERROR`、`TIMEOUT`、`TRANSCODE_FAILED`、`OUTPUT_INVALID`、`IO_ERROR`、`INTERNAL`。旧 Demo 不分类。
- **Worker 围栏**：重投不抢占租约内任务；上传前、master 前同步续租。**OutboxPublisher** 用条件 UPDATE 领取，可多实例。

**配置项**（默认值只是开发默认，不是基准或承诺）：

| 位置 | 名称 | 默认 | 含义 |
|---|---|---|---|
| Worker 环境变量 | `WORKER_LEASE_SECONDS` | 1800 | 租约时长 |
| | `WORKER_HEARTBEAT_SECONDS` | 60 | 续租间隔；超过租约 1/3 时自动压到 1/3 |
| | `WORKER_FFPROBE_TIMEOUT_SECONDS` / `WORKER_FFMPEG_TIMEOUT_SECONDS` | 120 / 3600 | 超时后杀子进程，记 `TIMEOUT`（可重试） |
| | `WORKER_RETRY_BASE_MS` / `WORKER_RETRY_MAX_MS` / `WORKER_RETRY_JITTER` | 10000 / 600000 / 0.5 | 退避基数、上限、随机缩短比例 |
| | `WORKER_REQUEUE_DELAY_MS` | 60000 | 别的 Worker 租约内时多久后再看 |
| | `WORKER_SWEEP_INTERVAL_SECONDS` / `WORKER_SWEEP_PENDING_AGE_SECONDS` | 60 / 120 | 补偿扫描间隔、PENDING / 逾期 RETRYING 的宽限 |
| | `WORKER_METRICS_PORT` | 9100 | `/metrics` 端口 |
| vod-api `vod.outbox.*` | `batch-size` / `max-backoff-seconds` / `claim-seconds` / `publish-interval-ms` | 20 / 300 / 60 / 5000 | 环境变量 `VOD_OUTBOX_BATCH_SIZE` 等 |
| vod-api `vod.metrics.*` | `refresh-interval-ms` | 15000 | 积压 gauge 刷新间隔，环境变量 `VOD_METRICS_REFRESH_INTERVAL_MS` |

**指标名**：vod-api `vod_jobs_backlog{status}`、`vod_jobs_oldest_age_seconds{status}`、`vod_outbox_pending`、`vod_outbox_oldest_pending_age_seconds`；Worker `vod_transcode_jobs_total{result}`、`vod_transcode_seconds`、`vod_transcode_inflight`、`vod_worker_heartbeat_timestamp_seconds`、`vod_worker_lease_lost_total`、`vod_worker_drop_fenced_total`；RabbitMQ `rabbitmq_queue_messages_ready{queue}`（`rabbitmq:15692/metrics/per-object`）。Prometheus 对 worker 用 `dns_sd_configs`，不设告警阈值。

**DLQ 运维**（不是租户 API）：

```bash
docker compose run --rm --no-deps -v "$PWD/scripts:/scripts:ro" worker python /scripts/dlq.py list
docker compose run --rm --no-deps -v "$PWD/scripts:/scripts:ro" worker python /scripts/dlq.py replay --job-id 12
```

`list` 读完全部放回；`replay` 只接受库里 `FAILED` 的 Core 任务（否则退出码 2），重置任务与资产、写新 outbox，提交后再移除 DLQ 消息。**故障注入**：`python3 scripts/fault_inject.py [--only kill,dup,minio,rabbitmq,cancel]`，compose 已启动时在仓库根目录运行。

**affected rows 语义**：PyMySQL 只统计实际变化的行，Connector/J 默认返回匹配行数。Worker 的条件 UPDATE 让「匹配」必然伴随「变化」，做不到的在 0 行时回查；vod-api 配额条件 UPDATE 依赖匹配行数，不要在 JDBC URL 里打开 `useAffectedRows`。

**仍未关闭的风险**：`fault_inject.py`、Worker 镜像、`rabbitmq_prometheus` 与 DNS 发现的实际抓取在 Docker 下尚未验证；Outbox 领取依赖实例间时钟偏差远小于 `claim-seconds`；`transcode.retry` 用消息级 TTL 有队头阻塞（Core 有 sweeper 兜底）；取消与 Worker 的加锁顺序、固定前缀并发写、取消后残留产物由 Phase 4 第 3、4、9 项处理。

## Phase 2 收尾（已合并到 `main`，`5a8b76a`）

当前上传与资产接口的行为：

- 迁移 V5–V8：`vod_storage_object`（回填旧 `source_*`）、会话 `object_key`/`expires_at`/`checksum`（旧会话按迁移时刻到期）、凭证 `expires_at`（NULL 不过期）、租户 `storage_quota_bytes`/`storage_used_bytes`。
- `vod_media_asset.source_bucket/source_key/source_size` 继续与 `vod_storage_object` 同事务双写，Python Worker 不改。
- `POST /api/v1/uploads` 可选 body `filename`（只决定 key 扩展名）；返回新增 `expires_at`。同一 Idempotency-Key 重放时预签名有效期不超过会话剩余时间，会话过期返回 409。
- `complete`：已有任务时直接幂等返回；否则会话须为 `OPEN` 且未过期（409），HEAD 校验扩展名/MIME（415）、空对象（422）、大小上限（413），配额超限 403；失败时不建任务、不写 outbox。
- 配额：`storage_quota_bytes` 为 NULL 时取 `vod.quota.default-storage-bytes`，**默认 0 = 不限制**；`<= 0` 均表示不限制。占用在任务插入成功后用一条条件 UPDATE 原子累加。
- `GET /api/v1/assets?limit=&cursor=`：按 id 倒序的游标分页，返回 `next_cursor`（null 表示没有下一页）；默认页大小与上限见 `vod.api.page-size-*`。
- scope：上传接口要 `upload`，资产与播放要 `asset`，任务要 `job`，`*` 全部；不足返回 403。Webhook、用量接口暂未加 scope。
- 超时、页大小、对象上限、MIME 白名单都在 `application.yml` 的 `vod.upload.*` / `vod.quota.*` / `vod.api.*`，默认值只是开发默认，不是基准或承诺。

## 目录

- `api/`：FastAPI Drama Demo、旧版上传/播放接口和 Core 适配器。
- `vod-api/`：Java 21 控制面、Flyway 数据库迁移及测试。
- `worker/`：共享转码 Worker，处理旧版和 Core 任务。
- `console/`：Vue + TypeScript 管理控制台源代码。
- `nginx/html/`：短剧播放页及已构建的控制台静态文件。
- `mysql/`、`docker-compose.yml`：数据结构和本地服务编排。
- `docs/`：路线图、阶段契约、API 与消息 Schema。
- `scripts/`：契约、桥接、HLS、客户端和端到端检查。

## 启动

需要 Docker 与 Compose 插件。完整端到端脚本需要 Python 3 和本机 FFmpeg；重建控制台需要 Node.js/npm。

```bash
# 可选：生产或共享环境先生成密钥写进 .env（不要提交）；dev 下不写也能起，只会打警告
#   HLS_SECRET=$(openssl rand -hex 32) TOKEN_SECRET=… ORIGIN_SECRET=… VOD_PLAY_TOKEN_SECRET=…，非 dev 再设 VOD_ENV=prod
docker compose up -d --build
docker compose ps
# 可选监控
docker compose --profile obs up -d prometheus
```

如 Docker 守护进程以 `--iptables=false` 运行，可附加 `docker-compose.noiptables.yml` 覆盖配置。普通 Docker Desktop/Linux 环境不需要该文件。

| 地址 | 用途 |
|---|---|
| http://localhost:8080 | 短剧播放页（可用 `?ep=1` 打开指定集；页头登录 / 注册，付费集用演示金币解锁） |
| http://localhost:8080/vod-console/ | 平台控制台（Vue；管理登录用 `VOD_ADMIN_USERNAME`/`VOD_ADMIN_PASSWORD` 种子的账号） |
| http://localhost:8080/console.html | 旧版静态控制台（只粘贴 API Key） |
| http://localhost:8080/api/... | Drama Demo API |
| http://localhost:8080/api/v1/... | VOD Core API |
| http://localhost:9000 | 预签名上传网关 |
| http://localhost:9001 | MinIO 控制台 |
| http://localhost:15672 | RabbitMQ 管理台 |
| http://localhost:9090 | Prometheus（需启用 `obs` profile） |

网页静态文件挂载自 `nginx/html`，更新后刷新即可。修改 API 或 Worker 后运行 `docker compose up -d --build api worker vod-api`。控制台源代码位于 `console/`；运行 `npm ci && npm run build` 会更新 `nginx/html/vod-console/`。手机访问请使用运行 Docker 主机的局域网地址。

## 检查

[![CI](https://github.com/TinyPandar/drama-vod/actions/workflows/ci.yml/badge.svg)](https://github.com/TinyPandar/drama-vod/actions/workflows/ci.yml)
[![Compose live](https://github.com/TinyPandar/drama-vod/actions/workflows/compose-live.yml/badge.svg)](https://github.com/TinyPandar/drama-vod/actions/workflows/compose-live.yml)

需要整套 Compose 栈的脚本由 `.github/workflows/compose-live.yml` 跑，见上文「路线 A」。

CI（`.github/workflows/ci.yml`）在 push 到任意分支、向 `main` 提 PR 时自动跑，三个 job 并行：`vod-api` 用 `mysql:8.4` 服务容器跑 `mvn -B test`（H2 用例和 `MysqlSemanticsTest`、`Phase5MysqlTest`、`Phase6MysqlTest` 一起跑，MySQL 用例缺失或被跳过算失败）；`python` 跑 `contract_check.py` 和全部 `scripts/test_*.py`（`test_fencing_mysql.py` 连服务容器，任何脚本非零退出或出现 skipped 算失败）；`console` 跑 `npm ci && npm run build`，并检查提交的 `nginx/html/vod-console/` 产物没过期。CI **不跑**需要整套 Compose 的脚本（`e2e.py`、`*_live.py`、`fault_inject.py`、`kill_recover_submit.py`、`browser_check.py`、`nginx_local_check.py`），也不发布、不部署、不用 secrets。

```bash
python scripts/contract_check.py
python scripts/test_bridge.py
python scripts/test_hls_verify.py
python scripts/test_vod_client.py
python scripts/test_worker_fencing.py
python scripts/test_worker_reliability.py   # Python 3.10+
python scripts/test_dlq.py
python scripts/test_output_gc.py
python scripts/test_playback_contract.py
python scripts/test_api_playback.py         # Python 3.10+，需要 fastapi、httpx
python scripts/test_transcode_template.py
python scripts/nginx_local_check.py         # 需要本机 nginx（带 secure_link）
python scripts/e2e.py        # 需要 Docker 服务和本机 FFmpeg
python scripts/playback_live.py             # 需要 Docker 服务、本机 FFmpeg 和 PyMySQL：Core 播放链路
python scripts/platform_live.py             # 需要 Docker（叠加 docker-compose.platform-live.yml）、FFmpeg、管理员种子：Phase 5 平台验收
# 需要 Docker：真实 MySQL 围栏与故障注入
MYSQL_HOST=127.0.0.1 MYSQL_USER=root MYSQL_PASSWORD=<root 口令> python scripts/test_fencing_mysql.py
python scripts/fault_inject.py
```

Java 控制面测试：在 `vod-api/` 下运行 `mvn test`（需要 Java 21 和 Maven）。控制台生产构建：在 `console/` 下运行 `npm ci && npm run build`。

旧 Demo e2e 覆盖上传、转码、播放签名和付费集播放。轻量脚本覆盖接口契约、Core/旧 Worker 路由隔离、半成品 HLS 拒绝和 API 适配器请求。Core 仍在演进中，启动 Compose 栈不等同于完成浏览器到 Core 的端到端验收。

## 已知边界

- Drama Demo 有自己的观众账号和演示金币（路线 C），没有真实支付、找回口令或充值。
- 旧 Demo 的读接口（`/hls/`、`/keys/{id}`、`/api/playback/`）只为路线 C 之前发出的地址保留；已有旧剧集用 `api/migrate_core.py` 迁到视频云。
- Worker 输出 HLS/TS，尚未实现 fMP4/CMAF、H.265、Widevine/FairPlay DRM 或多 CDN 调度。
- Demo 播放计数采用定时回刷，未承诺精确一次。
