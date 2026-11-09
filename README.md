# drama-vod：短剧点播示例

Docker Compose 点播系统，包含两条并行演进的路径：旧版 Drama Demo（FastAPI）和新版 VOD Core 控制面（Java 21 + Spring Boot）。播放页仍使用 Demo；管理控制台调用 `/api/v1`；两条路径共享 Python 转码 Worker。

媒体流程：**预签名直传 → RabbitMQ → FFmpeg 多码率 HLS → Nginx 签名校验与缓存 → hls.js 播放**。HLS 桶保持私有，API 验证回源密钥后为 Nginx 提供对象。旧 Demo 的付费集使用 AES-128 HLS。MySQL 保存数据，Redis 提供热点缓存和播放计数，Prometheus 是可选监控。

路线图见 [docs/panda-vod-roadmap.html](docs/panda-vod-roadmap.html)，旧接口兼容约定见 [docs/phase-0-contract.md](docs/phase-0-contract.md)。

## 分支方案：Phase 2 收尾

本分支：`plan/phase-2-wrapup`。这里只放修改方案，不在这里直接改业务代码。

协作方式：其他 agent 请在**本分支**上按下面清单做最小改动开发；不要自行合并到 `main`，也不要另开平行分支把同一批问题做散。开发完后由「短剧视频服务器开发bot」检查，没问题再合并。基线提交是 `2d4d32e`（`main` 当时比 `origin/main` 超前 2 个提交：`eed9b9e` 补了 outbox 发布与并发 complete 幂等，`2d4d32e` 补了资产 `TRANSCODING`/`FAILED` 的 `claim_version` 围栏）。

对照文档：[路线图](docs/panda-vod-roadmap.html) 第 05 节数据模型、第 06 节 API、第 09 节 Phase 2，以及针对 `cf644f7` 的验收结论「Phase 2 主链路完成，模型简化」。不要编造性能数字；新的超时、分页大小、对象上限请写成可配置项，不要写成基准或承诺。

### 已经完成，不要重做

- 租户表 `vod_tenant`，以及查询里用认证上下文里的 `tenant_id` 隔离（不要信客户端传来的租户）。
- API Key 哈希校验：`vod_api_credential.key_hash` + `ApiKeyFilter`（`X-Api-Key` / Bearer）。
- `vod_upload_session` + `vod_media_asset`，`POST /api/v1/uploads` 返回预签名 PUT，大文件不经过 API。
- `POST /api/v1/uploads/{id}/complete`：确认对象存在、记录大小、同一事务写 `vod_transcode_job` + `vod_outbox`。
- 顺序或并发重复 complete 返回同一个 `job_id`（撞 `uk_asset_profile` 时回读，不返回 500）。
- Outbox Publisher 与 RabbitMQ publisher confirms（这是 Phase 3，已经落地，本分支不要重写）。
- Worker 对任务和资产 `TRANSCODING`/`FAILED` 的 `claim_version` 围栏（这也是 Phase 3，已经落地）。
- Python Worker 继续消费带 `job_id` / `origin` 的兼容消息，不要为了 Phase 2 去改转码流水线。

### 本分支要补的问题

按建议顺序做。新的表结构用 Flyway **V5 及之后**，不要改 V1–V4。

1. **补独立的 `storage_object`**
   路线图第 05 节要求：`bucket`、`object_key`、`size`、`etag`、`checksum`、`content_type`、`role`，用来描述对象存储里的文件。现在这些信息散落在 `vod_media_asset.source_bucket` / `source_key` / `source_size`。应新增 `vod_storage_object`，让 `vod_media_asset` 通过 `source_object_id` 引用源对象。兼容旧数据：迁移时把已有 `source_*` 回填成一条 `role=SOURCE` 的对象行。Worker 和播放路径如果还读 `source_bucket`/`source_key`，要么暂时双写并保持可读，要么一并改到通过 `storage_object` 取值，不要留下一半引用一半直读却对不上的状态。

2. **补齐 `vod_upload_session` 字段，并让会话会过期**
   路线图字段包括 `asset_id`、`object_key`、`expires_at`、`status`、`checksum`。现在表里只有 `tenant_id`、`asset_id`、`idempotency_key`、`status`。预签名 URL 的 `expires_in` 目前是 900 秒，但会话本身不会过期，过期后仍可 complete。应写入 `object_key`、`expires_at`（以及客户端或后续 HEAD 得到的 checksum，没有就不编），`complete` 时拒绝已过期或非 `OPEN` 的会话。

3. **把 complete 的 HEAD 校验做完整**
   Phase 2 要求 complete 后服务端 HEAD 校验对象再创建任务。现在 `S3ObjectStorage` 已经 `headObject`，但 `ObjectStorage` 只暴露了 `exists` 和 `size`，`UploadService.complete` 只检查对象在不在以及大小。应按第 08 节补：记录 etag / content_type，校验扩展名与可接受 MIME，拒绝超过配置上限的对象。不要只信客户端声明。把 HEAD 元数据写进 `storage_object`。

4. **上传占用配额要能拒绝，而不只是记账**
   `complete` 会调用 `usage.addUpload` 累加 `vod_usage_daily.storage_bytes`，但没有任何上限判断，库里也没有配额字段。若本分支要对齐「上传校验配额」，请增加明确的配额读取（租户字段或配置项）和超限拒绝；默认值写在配置里，不要编造业务数字。如果这次只先把校验钩子和测试准备好、配额默认「不限制」，要在实现说明里写清楚。

5. **`GET /api/v1/assets` 要分页**
   路线图第 06 节：分页查询当前租户媒体资产。现在 `UploadService.assets` 是 `ORDER BY id DESC` 一次性全表。应加上分页参数（页码或 cursor 均可，选一种并写进接口），只返回当前租户，默认页大小走配置。

6. **`GET /api/v1/assets/{id}` 返回已经有的媒体信息**
   `V2__worker_columns.sql` 已经给资产加了 `hls_prefix`、`duration_ms`、`width`、`height`，详情接口却只返回 `id` / `status` / `source_size` / `encrypted`。应把这些字段以及源对象的关键元数据一并返回。仍必须走租户可见性检查。

7. **创建上传会话的 `Idempotency-Key` 要做成原子的**
   `POST /api/v1/uploads` 已支持可选 `Idempotency-Key`，但实现是先 SELECT 再 INSERT，两个并发请求可能都看不到对方，从而创建两份资产和会话。应改成与 complete 类似的原子处理（唯一键冲突后回读，或等价的 `INSERT ... ON DUPLICATE KEY`），保证同一租户同一 key 只对应一份 session/asset。控制台或 Demo 客户端没带这个头不是本分支必做项。

8. **API Key 补上过期，并真正校验 scope**
   这是 Phase 2「实现 API Key」和第 08 节的重叠项。`vod_api_credential` 已有 `scopes`、`status`，缺 `expires_at`（路线图第 05 节写了）。`TenantPrincipal.allows()` 已经写好，但没有调用点：`ApiKeyFilter` 只查哈希和 `ACTIVE`。应在过滤器里拒绝过期 key，并在上传、资产、任务等接口调用 `allows("upload")` 等 scope（现有约定是逗号分隔，`*` 表示全部）。不要顺便重写成 Spring Security + JWT 管理端登录，那不是 Phase 2。

9. **补 `GET /api/v1/jobs/{id}`**
   路线图第 06 节有单任务查询；现在只有租户下的任务列表。应返回当前租户可见的单个任务（状态、attempts、claim_version、错误信息等已有列），跨租户或不存在时走现有的不可见处理，不要泄露别的租户。

### 本分支不要做

这些不是 Phase 2 正文，留给后续分支，避免把范围拉大：

- Phase 3 已完成或剩余项：Outbox Publisher、publisher confirms、claim/租约/fencing、取消任务 API、多实例/kill/MinIO 故障注入测试、`steal=redelivered` 的并行执行问题。
- Phase 4：Core 播放 token、加密 Core 资产的 `/keys` 链路、ORIGIN_SECRET 加固、QoE 指标。
- Phase 5：控制台的租户/API Key/模板管理、Webhook 重试与 DNS 重绑定、转码模板 `transcode_template` 和 `POST /assets/{id}/transcodes`。
- Phase 6：Multipart 断点续传、缩略图、字幕、fMP4/CMAF、H.265/AV1、多 CDN。
- 不要重写 Worker 转码、不要改 Windows 或 `~/.claude/settings.json`、不要把密钥写进仓库。

### 验收时至少覆盖

- 现有 `vod-api` 测试（尤其 `UploadIsolationTest`）仍然通过。
- 每个改动点有对应测试：过期会话不能 complete；HEAD 校验失败不建任务；分页只看到本租户；Idempotency-Key 并发只建一份会话；过期或 scope 不足的 key 被拒绝；`storage_object` 与资产引用一致。
- 有 Java/Maven 就跑 `cd vod-api && mvn test`；不要在没有跑过的情况下声称通过。

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
