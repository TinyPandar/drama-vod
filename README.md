# drama-vod：短剧点播示例

Docker Compose 点播系统，包含两条并行演进的路径：旧版 Drama Demo（FastAPI）和新版 VOD Core 控制面（Java 21 + Spring Boot）。播放页仍使用 Demo；管理控制台调用 `/api/v1`；两条路径共享 Python 转码 Worker。

媒体流程：**预签名直传 → RabbitMQ → FFmpeg 多码率 HLS → Nginx 签名校验与缓存 → hls.js 播放**。HLS 桶保持私有，API 验证回源密钥后为 Nginx 提供对象。旧 Demo 的付费集使用 AES-128 HLS。MySQL 保存数据，Redis 提供热点缓存和播放计数，Prometheus 是可选监控。

路线图见 [docs/panda-vod-roadmap.html](docs/panda-vod-roadmap.html)，旧接口兼容约定见 [docs/phase-0-contract.md](docs/phase-0-contract.md)。

## 分支方案：Phase 4 播放与分发

本分支：`plan/phase-4`，基线 `main` = `05ce971`。本节只是方案，代码尚未实现。

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
docker compose up -d --build
docker compose ps
# 可选监控
docker compose --profile obs up -d prometheus
```

如 Docker 守护进程以 `--iptables=false` 运行，可附加 `docker-compose.noiptables.yml` 覆盖配置。普通 Docker Desktop/Linux 环境不需要该文件。

| 地址 | 用途 |
|---|---|
| http://localhost:8080 | 短剧播放页（可用 `?ep=1&uid=7` 打开指定集） |
| http://localhost:8080/console.html | VOD Core 管理控制台 |
| http://localhost:8080/api/... | Drama Demo API |
| http://localhost:8080/api/v1/... | VOD Core API |
| http://localhost:9000 | 预签名上传网关 |
| http://localhost:9001 | MinIO 控制台 |
| http://localhost:15672 | RabbitMQ 管理台 |
| http://localhost:9090 | Prometheus（需启用 `obs` profile） |

网页静态文件挂载自 `nginx/html`，更新后刷新即可。修改 API 或 Worker 后运行 `docker compose up -d --build api worker vod-api`。控制台源代码位于 `console/`；运行 `npm ci && npm run build` 会更新 `nginx/html/vod-console/`。手机访问请使用运行 Docker 主机的局域网地址。

## 检查

```bash
python scripts/contract_check.py
python scripts/test_bridge.py
python scripts/test_hls_verify.py
python scripts/test_vod_client.py
python scripts/test_worker_fencing.py
python scripts/test_worker_reliability.py   # Python 3.10+
python scripts/test_dlq.py
python scripts/e2e.py        # 需要 Docker 服务和本机 FFmpeg
# 需要 Docker：真实 MySQL 围栏与故障注入
MYSQL_HOST=127.0.0.1 MYSQL_USER=root MYSQL_PASSWORD=<root 口令> python scripts/test_fencing_mysql.py
python scripts/fault_inject.py
```

Java 控制面测试：在 `vod-api/` 下运行 `mvn test`（需要 Java 21 和 Maven）。控制台生产构建：在 `console/` 下运行 `npm ci && npm run build`。

旧 Demo e2e 覆盖上传、转码、播放签名和付费集播放。轻量脚本覆盖接口契约、Core/旧 Worker 路由隔离、半成品 HLS 拒绝和 API 适配器请求。Core 仍在演进中，启动 Compose 栈不等同于完成浏览器到 Core 的端到端验收。

## 已知边界

- Drama Demo 使用调用方提供的 `user_id` 和演示解锁流程；Core 租户 API Key 尚未替代 Demo 用户体系。
- Core 控制面与旧 Demo 并行运行，播放链路尚未整体迁移。
- Worker 输出 HLS/TS，尚未实现 fMP4/CMAF、H.265、Widevine/FairPlay DRM 或多 CDN 调度。
- Demo 播放计数采用定时回刷，未承诺精确一次。
