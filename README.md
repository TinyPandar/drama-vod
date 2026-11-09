# drama-vod：短剧点播示例

Docker Compose 点播系统，包含两条并行演进的路径：旧版 Drama Demo（FastAPI）和新版 VOD Core 控制面（Java 21 + Spring Boot）。播放页仍使用 Demo；管理控制台调用 `/api/v1`；两条路径共享 Python 转码 Worker。

媒体流程：**预签名直传 → RabbitMQ → FFmpeg 多码率 HLS → Nginx 签名校验与缓存 → hls.js 播放**。HLS 桶保持私有，API 验证回源密钥后为 Nginx 提供对象。旧 Demo 的付费集使用 AES-128 HLS。MySQL 保存数据，Redis 提供热点缓存和播放计数，Prometheus 是可选监控。

路线图见 [docs/panda-vod-roadmap.html](docs/panda-vod-roadmap.html)，旧接口兼容约定见 [docs/phase-0-contract.md](docs/phase-0-contract.md)。

## 分支方案：Phase 3 转码任务可靠性

本分支：`plan/phase-3`，基线 `main` = `5a8b76a`。这里只放修改方案，不直接改业务代码。

协作方式：其他 agent 请在**本分支**上按下面清单做最小改动开发；不要自行合并到 `main`，也不要另开平行分支。开发完后由「短剧视频服务器开发bot」检查，没问题再合并。

对照文档：[路线图](docs/panda-vod-roadmap.html) 第 07 节（异步任务可靠性）、第 09 节 Phase 3（「Outbox Publisher、幂等 Claim、租约续期、fencing token、退避重试、取消和 DLQ；修复小分辨率 ladder 不应被放大；补充 Worker 多实例和故障注入测试」）、第 10 节第 3、4、8 条验收。目标不是 Exactly Once，而是**至少一次投递 + 幂等消费 + 可恢复状态机**。

### 已经完成，不要重做

- complete 同一事务写 `vod_transcode_job` + `vod_outbox`，提交后快速发布（`UploadService.java:120-145, 288-298`）；并发 complete 返回同一 `job_id`。
- `OutboxPublisher` 轮询 PENDING、publisher confirms、指数退避（`OutboxPublisher.java`、`RabbitTranscodePublisher.java:171-180`，`eed9b9e`）。
- Worker 原子 claim、`attempts`、租约 + 心跳续租、`claim_version` 围栏（`worker.py:47-69, 163-170`）；资产 `TRANSCODING`/`FAILED` 围栏（`fencing.py`，`2d4d32e`）。
- 主队列 / 延迟重试队列 / 死信队列拓扑（`common.py:11-45`），失败后进 retry 或 DLQ（`worker.py:250-257`）；补偿扫描 sweeper（`worker.py:278-313`）。
- 小分辨率不放大（`worker.py:181-186`）；HLS 本地校验后才上传、`master.m3u8` 最后上传（`worker.py:126-133, 196`）。
- `GET /api/v1/jobs/{id}` 及租户可见性（Phase 2 已做）。

### 本分支要做的

建议按顺序做。新表结构用 Flyway **V9 及之后**，不要改 V1–V8。旧 Demo 链路（`transcode_job` / `video_asset`）行为按 `docs/phase-0-contract.md` 冻结，新列只加在 `vod_*` 表，Worker 按 `origin` 区分，不要让旧表因缺列报错。

1. **RabbitMQ 重投时不再抢占仍在租约内的任务**
   现状：`on_message` 传 `steal=bool(method.redelivered)`（`worker.py:268-269`），`claim` 在 `steal` 时对任何 `RUNNING` 都能抢（`worker.py:50`）。连接断开、broker 重启都会重投，原 Worker 可能还活着，导致两个进程并行跑同一任务、往同一前缀写产物；旧进程只有在下一次心跳（`worker.py:166`，60 秒）才发现围栏丢失。租约 30 分钟写死（`worker.py:24`）。
   要改：去掉 `steal`，`RUNNING` 只在 `locked_until < NOW()` 时可抢；租约未到期走现有 `requeue`。租约时长、心跳间隔改成环境变量（心跳须明显小于租约）。上传产物前，以及上传 `master.m3u8` 前，同步复核一次围栏（例如 `renew_lease` 返回 False 就停止），不要只看心跳线程的事件。
   验收：单测覆盖「重投 + 租约有效 → 不抢、requeue」「租约过期 → 可抢、claim_version+1」「上传前围栏丢失 → 不传 master、不写 READY」。

2. **READY 提交也带围栏，并在真实 MySQL 上验证语义**
   现状：成功事务里 `UPDATE vod_media_asset SET status='READY'`（`worker.py:214-215`）和旧表的 READY 更新没有 `claim_version` 条件，靠后面任务 `UPDATE` 影响 0 行再 `rollback` 兜底（`worker.py:216-222`）。`fencing.py:343-351` 依赖「MySQL 只统计实际变化的行」并补一次 SELECT。这些都只在假游标单测里跑过，没有在 MySQL 8.4 上验证；Java 侧 Connector/J 默认返回匹配行数，和 PyMySQL 可能不同，也请一并核对（配额条件 UPDATE、outbox 状态更新）。
   要改：先执行带 `claim_version` 的任务 `SUCCEEDED` 更新，只有影响 1 行才继续写资产 READY（或给 READY 更新加同样的 JOIN 围栏），不只依赖回滚。新增一个连真实 MySQL 的测试脚本（如 `scripts/test_fencing_mysql.py`，连 compose 里的 mysql；有条件也可用 Testcontainers）。
   验收：真实 MySQL 上验证：旧 `claim_version` 写不了 READY/TRANSCODING/FAILED；状态本就相同时围栏判断仍正确；成功事务中途失败时资产与任务一起回滚。没跑真实 MySQL 就写明未验证。

3. **取消任务 API**
   现状：没有任何取消入口（全仓库无 cancel），状态里也没有 `CANCELLED`。
   要改：`POST /api/v1/jobs/{id}/cancel`，scope `job`，跨租户/不存在走现有不可见处理。一条条件 UPDATE：`PENDING`/`RETRYING`/`RUNNING` → `CANCELLED`，同时 `claim_version+1`、清租约；资产（非 READY 时）同事务改为 `CANCELLED`。`SUCCEEDED`/`FAILED` 返回 409；已 `CANCELLED` 幂等返回 200。Worker 侧：现有 claim 条件已不会领取 `CANCELLED`，心跳续租因 `status='RUNNING'` 条件失败而围栏丢失；建议把 FFmpeg 改为可中断（围栏丢失时终止子进程），不要整体重写流水线。sweeper 不重投 `CANCELLED`。只做 Core 任务，旧 Demo 任务不加取消。取消后重新转码属于 Phase 5 的 `POST /assets/{id}/transcodes`，这里不做。
   验收：Java 测试覆盖各状态转换、409、幂等、跨租户 404、scope 不足 403；Worker 单测覆盖「运行中被取消 → 不写 READY、不进 retry/DLQ」。

4. **错误分类、退避加抖动、`error_code`，RETRYING 兜底**
   现状：所有异常都按可重试处理（`worker.py:231-242`），退避 `10s × 2^(n-1)` 无 jitter；`vod_transcode_job` 没有路线图第 05 节的 `error_code`，只有 `last_error` 文本；`ffprobe`/`ffmpeg` 没有超时（`worker.py:72-76`）。sweeper 只救 `PENDING` 和过期 `RUNNING`（`worker.py:284-295`），`RETRYING` 的重试消息若丢失就无人兜底；`updated_at` 没有 `ON UPDATE`（`V2__worker_columns.sql:6`），之后基本不会变。
   要改：V9 给 `vod_transcode_job` 加 `error_code`、`next_attempt_at`（名称可自定，写进实现说明）。Core 失败时写 `error_code`：输入不可解析、无视频流等不可重试错误直接 `FAILED` + DLQ；对象存储/数据库/网络/超时等可重试错误按指数退避加 jitter。FFmpeg/ffprobe 超时走环境变量。sweeper 兜底「`RETRYING` 且 `next_attempt_at` 已过」。状态更新时维护 `updated_at`。状态名保持现有 `RETRYING`，不引入 `RETRY_WAIT`/`QUEUED`（Phase 0 契约冻结了这些状态）。
   验收：单测覆盖不可重试错误首次即 FAILED、可重试错误退避在 jitter 区间内、最后一次进 DLQ；`GET /jobs/{id}` 返回 `error_code`。

5. **OutboxPublisher 支持多实例**
   现状：`publishPending()` 只有 JVM 内 `synchronized`，注释写明「单实例即可，不需要分布式锁」（`OutboxPublisher.java:93-112`）；两个 vod-api 实例会读到同一批 PENDING 行并重复发布。批大小和退避上限写死（`OutboxPublisher.java:99-100`）。
   要改：发布前先领取行，二选一：V9 给 `vod_outbox` 加 `locked_until`/`locked_by`，用条件 UPDATE 领取（H2 与 MySQL 都能测）；或短事务 `SELECT ... FOR UPDATE SKIP LOCKED`（需确认 H2 测试可用）。领取过期可被别的实例接手。批大小、退避上限、领取时长改为配置项。保留 complete 后的快速发布和 Worker sweeper 作为兜底。
   验收：测试中两个 publisher 实例对同一批行，各行只被一个实例发布；发布成功但标记前崩溃会被再次发布（至少一次，下游幂等）。

6. **DLQ 查看与重放**
   现状：`transcode.dlq` 只声明、只写入（`common.py:12, 44`，`worker.py:253-254`），没有任何消费者或运维工具。
   要改：增加运维脚本（如 `scripts/dlq.py`）：`list` 只看不丢（列出 DLQ 消息及对应任务在库里的状态/`error_code`）；`replay --job-id`：仅当 Core 任务在库里是 `FAILED` 时，在一个事务里把任务重置为 `PENDING`（清 `error_code`/`last_error`、`attempts` 归零、`claim_version+1`）、资产改回 `UPLOADED`、插入新的 `vod_outbox` 行，由 OutboxPublisher 发布，然后移除对应 DLQ 消息。以数据库为准，不要直接把 DLQ 原消息转发回主队列。这是运维工具，不做租户 API（管理端鉴权属于 Phase 5）。
   验收：脚本有单测或 compose 下的演示步骤；重放后任务能正常 READY；非 FAILED 任务拒绝重放。

7. **Prometheus：任务积压、outbox、DLQ、Worker 心跳**
   现状：`prometheus.yml` 只抓 `api`、`worker:9100`、`vod-api`；vod-api 没有自定义指标；Worker 只有 `vod_transcode_jobs_total`、`vod_transcode_seconds`、`vod_transcode_inflight`（`worker.py:29-31`）。`docker compose --scale worker=2` 时 Prometheus 仍只抓一个目标。
   要改：vod-api 用 Micrometer 定时从库里读 gauge：各状态任务数、最老 PENDING/RETRYING 的等待秒数、outbox PENDING 数与最老等待秒数（刷新间隔走配置，查询要走索引或足够轻）。队列深度（含 `transcode.retry`、`transcode.dlq`）用 RabbitMQ 自带的 `rabbitmq_prometheus` 插件，不自己轮询管理 API。Worker 加心跳时间戳 gauge 和 lease_lost / drop_fenced 计数。Prometheus 对 worker 用 DNS 服务发现抓所有副本。不要写告警阈值或容量数字当承诺。
   验收：`/actuator/prometheus` 与 Worker `/metrics` 能看到新指标（测试或启动后 curl 截取）；README 写明指标名。

8. **多 Worker 与故障注入验证**
   现状：`kill_recover_submit.py` 只覆盖旧 Demo 且要手动 kill；`core_live.py:99-114` 只是插入一条假的过期 RUNNING 行；没有 MinIO、RabbitMQ 故障场景，也没有多 Worker 并发验证。
   要改：新增 `scripts/fault_inject.py`（基于 docker compose，Core API 链路），每个场景给出明确的预期结局：
   - kill 正在转码的 Worker → 租约过期或重投后由其他 Worker 完成，资产 READY；
   - `--scale worker=2` 并发多任务 + 人为重复投递 → 每个 `claim_version` 只有一个进程在跑（按日志 `job_start`/`claim_version` 检查），产物不重复；
   - 转码中途暂停/停止 MinIO 一段时间 → 重试后 READY，或超过次数后 FAILED 且带 `error_code`；
   - 重启 RabbitMQ → PENDING outbox 被重新发布，任务完成；
   - 运行中取消 → CANCELLED，不出现 READY。
   为了跑得快，测试可通过环境变量缩短租约和心跳；超时作为脚本参数。只报告真实跑出的结果，不写性能数字。
   验收：脚本可在本地 compose 上一条命令跑完并打印每个场景 PASS/FAIL；没环境就写明未运行。

9. **（Phase 2 遗留，顺带修）已完成的上传会话重放不再给 `put_url`**
   现状：`replay()` 只检查是否过期（`UploadService.java:249-252`），`COMPLETED` 会话用同一 `Idempotency-Key` 重放仍会拿到新的预签名 PUT，可能在转码期间覆盖已校验过的源文件，使 Worker 读到的源与 HEAD 记录的 etag 不一致。
   要改：非 `OPEN` 会话重放时不签发 `put_url`，返回会话、资产与已有任务信息（或 409，选一种写进实现说明）。
   验收：测试覆盖 COMPLETED 重放不含 `put_url`，OPEN 重放行为不变。

10. **文档对齐**
    `docs/phase-0-contract.md:139, 146` 仍写「冻结到 fencing 落地之前」「没有 fencing token」，已过时。更新 Worker 状态机一节：`claim_version` 围栏、重投不抢占、`CANCELLED`、`error_code`、DLQ 重放；README 补取消接口、新配置项和指标名。消息 v1 契约不改；`api/common.py` 与 `worker/common.py` 的 `transcode_message()` 保持一致（`contract_check.py` 会比对）。

**可选小项（不做不影响验收）**：默认 MIME 白名单加 `binary/octet-stream`（`application.yml:26`，AWS S3 对未声明类型的对象可能返回这个值），配一条测试。只改配置，不改校验逻辑。

### 本分支不要做

- 产物写入任务专属前缀再原子切换 `hls_prefix`（第 07 节提到）：会动 `api/origin_path.py:13` 和 Nginx 回源路径，放到 Phase 4 和播放链路一起做。本分支用第 1、2 项的围栏和上传前复核来防并行写。
- Phase 4：Core 播放 token、加密 Core 资产的 `/keys` 链路、`ORIGIN_SECRET` 默认 `dev` 的加固、QoE 指标。
- Phase 5：控制台（租户、API Key、模板）、Webhook 重试与 DNS 重绑定、`transcode.succeeded/failed` 事件、`transcode_template` 与 `POST /assets/{id}/transcodes`、租户侧的「重试任务」接口。
- Phase 6：Multipart 断点续传、缩略图、字幕/音轨、CMAF/fMP4、H.265/AV1、多 CDN。
- 第 08 节 FFmpeg 容器级 CPU/内存/磁盘限制（本分支只加超时）、v1 OpenAPI 全量补齐、Spring Security + JWT。
- 不要重写 Worker 转码流水线、不要换 MQ 或引入延迟消息插件、不要改旧 Demo 表结构与行为、不要改 Windows 或 `~/.claude/settings.json`、不要把密钥写进仓库。
- 已知但不在本分支修的：`transcode.retry` 用的是消息级 TTL，队头的长延迟消息会挡住后面的短延迟消息；以数据库状态为准，只在风险里说明。

### 约束

- Flyway V9 起，不改 V1–V8；SQL 要同时兼容 H2 MySQL 模式（测试）和 MySQL 8.4。
- 白盒理解现有代码后最小改动；保留现有 sweeper、快速发布、`claim_version` 语义。
- 租约、心跳、超时、退避上限、批大小、指标刷新间隔都写成配置项，默认值只是开发默认，不是基准或承诺；不要编造性能数字。

### 怎么测

- `cd vod-api && mvn test`（需要 Java 21 + Maven），现有 34 个用例须继续通过。
- `python3 scripts/test_worker_fencing.py`，以及新增的 Worker 单测。
- `python3 scripts/contract_check.py`、`test_bridge.py`、`test_hls_verify.py`、`test_vod_client.py`。
- 需要 Docker：`docker compose up -d --build` 后跑真实 MySQL 围栏脚本和 `scripts/fault_inject.py`。
- 没跑过的不要声称通过，写明原因。

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
python scripts/e2e.py        # 需要 Docker 服务和本机 FFmpeg
```

Java 控制面测试：在 `vod-api/` 下运行 `mvn test`（需要 Java 21 和 Maven）。控制台生产构建：在 `console/` 下运行 `npm ci && npm run build`。

旧 Demo e2e 覆盖上传、转码、播放签名和付费集播放。轻量脚本覆盖接口契约、Core/旧 Worker 路由隔离、半成品 HLS 拒绝和 API 适配器请求。Core 仍在演进中，启动 Compose 栈不等同于完成浏览器到 Core 的端到端验收。

## 已知边界

- Drama Demo 使用调用方提供的 `user_id` 和演示解锁流程；Core 租户 API Key 尚未替代 Demo 用户体系。
- Core 控制面与旧 Demo 并行运行，播放链路尚未整体迁移。
- Worker 输出 HLS/TS，尚未实现 fMP4/CMAF、H.265、Widevine/FairPlay DRM 或多 CDN 调度。
- Demo 播放计数采用定时回刷，未承诺精确一次。
