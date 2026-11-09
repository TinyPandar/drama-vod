# Phase 0 · 契约冻结

日期：2026-11-06。对照的是当前 `main` 代码，不是 [Panda VOD 路线图](panda-vod-roadmap.html) 里的目标形态。

路线图把系统拆成两块：

- **VOD Core**：上传、资产、转码、播放授权、分发。以后的控制面是 `/api/v1`，执行面继续是 Python Worker。
- **Drama Demo**：剧、集、解锁、播放页。通过 Core 的 API 使用视频能力，不进入 Core 的领域模型。

Phase 0 不改这条链路的行为。它只规定：换 Spring、加租户之前，哪些现有契约不能动。

检查（不需要 Docker）：

```bash
python3 scripts/contract_check.py
```

带 Docker 的行为检查仍是 `python3 scripts/e2e.py`。下面标了「e2e」的条目，就是这条脚本已经锁住的行为。

## 兼容级别

| 级别 | 含义 |
|---|---|
| 冻结 | Demo 还在调用、或 Nginx / Worker / e2e 依赖。换实现不能改语义。 |
| Demo | 只属于短剧站。Core 不实现。可以加字段，不能删播放页正在用的字段。 |
| 不承诺 | 现状如此，路线图明确要换。调用方不能依赖。 |

可以加 JSON 字段。不能改已有字段的含义，不能改下面写明的状态码。`detail` 文案不冻结。

## 边界

现在剧、集和资产在同一个 FastAPI 进程里。这是部署现状，不是领域边界。

Core 只应理解媒体资产。`drama`、`episode`、`user_unlock`、`episode_play_stat` 留在 Demo。Demo 的一集用资产 id 指向 Core，对应现在的 `episode.asset_id`。

`POST /api/episodes/{id}/upload-url` 仍然要求先有集。这是过渡接口：e2e 和现有上传脚本依赖它，所以先冻结。新的上传会话不该再要求 `episode_id`，那是 Phase 2 的 `/api/v1/uploads`。

## HTTP

机器可读的清单在 [contracts/openapi.yaml](contracts/openapi.yaml)。这里只写不能看丢的行为。

### 冻结

| 接口 | 必须保持 |
|---|---|
| `GET /healthz` | 200，`{"ok": true}` |
| `POST /api/episodes/{id}/upload-url` | 200 含 `asset_id`、`put_url`、`expires_in=900`。对象键是 `raw/{drama_id}/{episode_id}/{asset_id}/source.mp4`，桶 `vod-source`。同一集再次申请是新资产，不覆盖旧原片。 |
| `PUT` 预签名 URL | 直传对象存储，不经过 API。e2e 依赖 200。 |
| `POST /api/assets/{id}/complete` | 对象不存在 **409**。重复调用同一个 `job_id`，任务表只有一行（e2e）。200 含 `asset_id`、`job_id`、`job_status`。消息发送失败仍返回 200。 |
| `GET /api/assets/{id}` | 200 含 `asset.status`、`jobs[]`、`renditions[]`。e2e 用 `asset.status` 等到 `READY`。 |
| `GET /api/episodes/{id}/play?user_id=` | 未就绪 **409**。付费未解锁 **402**。200 含 `master_url`、`token`、`asset_id`、`encrypted`、`expires_at`、`next`。下一集未就绪时 `next` 为 null；下一集未解锁时只有 `episode_id` 和 `locked: true`。 |
| `GET /keys/{asset_id}` | 无 token、过期、资产不匹配都是 **401**。合法 token 返回 **16 字节**，`Cache-Control: no-store`（e2e）。`Authorization: Bearer` 和 `?token=` 都算数。 |
| `GET /hls/{sig}/{exp}/{asset}/{file}` | 签名错 **403**，过期 **410**，缓存键是 `{asset}/{file}`，不含签名（e2e 的 MISS/HIT）。 |

`user_id` 由调用方传入，这是 Demo 鉴权，不是 Core 的最终形态。换 Spring 时，旧的 `play?user_id=` 可以留在 Demo；Core 的播放授权改走租户身份。在 Demo 改完之前，这个查询参数不能从现有进程里拿掉。

`encrypted` 当前等于该集的 `is_paid`，不是 `video_asset.encrypted`。上传之后再改付费标记，播放响应和实际 m3u8 可能不一致。Demo 不要依赖这种改法。

### Demo

播放页 `nginx/html/index.html` 在用：

- `GET /api/dramas`：只返回 `ONLINE`，含 `ready_episodes`
- `GET /api/dramas/{id}`：`drama` + `episodes`
- `GET /api/episodes/{id}`：给 `?ep=` 深链补标题
- `POST /api/users/{id}/unlock`：不扣费，重复调用仍 200

建剧、建集也是 Demo：`POST /api/dramas`、`POST /api/dramas/{id}/episodes`。e2e 用它们准备数据。Core 不提供这两个接口；e2e 在 Demo 拆出去之后要改成先建资产、再由 Demo 关联。

`GET /api/episodes/{id}/stats`、`POST /api/admin/flush-play-counts`、`GET /api/rank/hot` 没有鉴权，不属于兼容承诺。热榜返回的是 Redis 元组，格式不要依赖。

`GET /api/playback/{asset}/master.m3u8?token=` 是给不能加请求头的原生 HLS 用的。分片地址必须仍落在原来的 `/hls/{sig}/{exp}/{asset}/` 下面，这样 Nginx 缓存和签名规则不用分叉。播放列表的具体文本可以变，付费集在 iPhone 上要仍能播。

## 播放签名

Nginx 和 API 必须用同一条算法。e2e 会改签名、改资产 id、改过期时间。

```
明文 = "{exp}/{asset_id} {HLS_SECRET}"
签名 = base64url(md5(明文))，去掉末尾的 =
URL  = /hls/{签名}/{exp}/{asset_id}/master.m3u8
```

注意明文里资产 id 和密钥之间有一个空格。签的是整个资产目录，不是单个文件。m3u8 里的相对路径因此自动带上同一段前缀。

播放 token：

```
payload = base64url( json.dumps({"u": user_id, "a": asset_id, "exp": exp}) )
token   = payload + "." + hex(hmac_sha256(TOKEN_SECRET, payload))
```

`json.dumps` 使用默认分隔符，也就是 `{"u": 1, "a": 2, "exp": 3}` 这种带空格的写法，键顺序固定为 `u`、`a`、`exp`。base64 保留 `=` 填充。Spring 若改成紧凑 JSON，现有播放器的 token 会全部失效。

默认有效期 `PLAY_URL_TTL=600` 秒。

## 对象与 HLS

| 对象 | 位置 |
|---|---|
| 原片 | 桶 `vod-source`，键 `raw/{drama_id}/{episode_id}/{asset_id}/source.mp4` |
| HLS | 桶 `vod-hls`，前缀 `assets/{asset_id}/` |
| master | `assets/{asset_id}/master.m3u8`，目录里最后上传 |
| 子列表 | `assets/{asset_id}/{档}/index.m3u8` |
| 分片 | `assets/{asset_id}/{档}/seg_#####.ts` |

档位名冻结为 `720p`、`540p`、`360p`。短边和视频码率是 720/2500、540/1200、360/600。源的短边小于某档时，该档不产出。源的短边小于 360 时，现在仍会退回产出 360p，这时会放大。e2e 用的是 720×1280，没有锁住这条退回。Phase 3 可以改成「小于 360 也不放大」，不算破坏本契约。

付费资产的 `#EXT-X-KEY` URI 是 `/keys/{asset_id}`。重试复用同一把 key。首片不超过约 1 秒，其后约 4 秒（e2e）。

Nginx 把 `/hls/{sig}/{exp}/{asset}/{file}` 回源到 `vod-hls/assets/{asset}/{file}`。

## 消息 v1

Schema：[contracts/transcode-message.v1.schema.json](contracts/transcode-message.v1.schema.json)。

发布端从这次开始必须发送：

```json
{"schema_version": 1, "job_id": 12, "asset_id": 9, "trace_id": "请求 id 或 null"}
```

队列名冻结：`transcode`、`transcode.retry`、`transcode.dlq`。消息持久化。`transcode.retry` 死信回 `transcode`。延迟用单条消息的 `expiration`，不是队列级 TTL。

消费者契约比发布端宽：

- 只认 `job_id`。资产状态从数据库读，不信消息体。
- 忽略未知字段。
- 没有 `schema_version` 的旧消息仍按 v1 处理。

`api/common.py` 和 `worker/common.py` 里的 `transcode_message()` 必须保持同一份。契约检查会比对这两个函数。

## Worker 状态机

任务唯一键是 `(asset_id, profile)`，profile 固定 `ladder_v1`。旧 Demo（`transcode_job`）和 Core（`vod_transcode_job`）共用一个 Worker，按消息里的 `origin` 区分；下表「冻结」的结果对旧 Demo 继续有效，Phase 3 新增的列和状态只在 `vod_*` 表上。

| 行为 | 是否冻结 |
|---|---|
| 抢任务：`PENDING` / `RETRYING`，或 `RUNNING` 且租约已过期；每次抢到 `claim_version` +1、`attempts` +1 | 冻结。RabbitMQ 重投（`redelivered`）不再抢占租约内的任务：原进程可能还活着，只能等租约过期。 |
| `claim_version` 围栏：任务 `SUCCEEDED`/`RETRYING`/`FAILED`、资产 `TRANSCODING`/`READY`/`FAILED` 都只在仍持有自己拿到的版本时写入；成功事务先写任务 `SUCCEEDED`，影响 1 行才写资产 `READY` | 冻结。旧 Worker 在租约过期后写不回结果。 |
| 上传产物前、上传 `master.m3u8` 前各同步续租一次，失败就停止，不传 master、不写 `READY` | 冻结「围栏丢失不出现半成品 master」。 |
| 租约默认 30 分钟、心跳 60 秒，`attempts` 最多 3 次 | 冻结次数和「失败会进死信」这个结果。租约、心跳是 Worker 环境变量，秒数不冻结。 |
| 失败退避：指数退避加 jitter，最后一次 `FAILED` 并进 `transcode.dlq`，资产 `FAILED` | 结果冻结，秒数不冻结。 |
| Core 失败分类：输入不可解析、无视频流、源对象不存在等不可重试错误首次即 `FAILED` + 死信；其余按退避重试。`vod_transcode_job.error_code` 记分类，`next_attempt_at` 记下次重试时间 | Core 冻结。旧 Demo 不分类，仍按次数重试。 |
| 别人正跑且租约未到期：延迟后再看；已终态或已取消则丢弃 | 冻结「重复消息不能跑出第二份产物」。 |
| 成功事务：rendition、资产 `READY`、集的 `asset_id` 和 `READY`、任务 `SUCCEEDED`，然后删 Redis `ep:{id}` | 冻结。master 必须最后上传。 |
| 取消（只有 Core）：`POST /api/v1/jobs/{id}/cancel` 把 `PENDING`/`RETRYING`/`RUNNING` 改成 `CANCELLED` 并 `claim_version` +1；在跑的 Worker 续租失败后杀掉 FFmpeg、丢弃结果，不进 retry/DLQ | 冻结。`CANCELLED` 是终态，不会被领取或补偿扫描。 |
| 补偿扫描：`PENDING` 超过 2 分钟，或 `RUNNING` 租约过期，重新投递；Core 另外兜底 `RETRYING` 且过了 `next_attempt_at` | 行为要保留。2 分钟这个数不冻结。 |
| DLQ 重放：运维脚本 `scripts/dlq.py replay` 只接受库里 `FAILED` 的 Core 任务，以数据库为准重置并写新 outbox，不转发 DLQ 原消息 | 运维工具，不是公开 API。 |

`claim_version` 落地后消息 v1 不用改，消费者本来就按 `job_id` 查库。已知缺口：同一资产的产物仍写在固定前缀 `assets/{asset_id}/`（Core 为 `assets/v1/{asset_id}/`），并发写靠上面的围栏和上传前复核收敛，任务专属前缀加原子切换放在 Phase 4。

## 表归谁

| 表 | 归属 | Phase 0 决定 |
|---|---|---|
| `video_asset` `asset_rendition` `transcode_job` `content_key` | Core | 列可以加，不能改唯一键 `(asset_id, profile)` 和 `(asset_id, name)`。`content_key` 明文是不承诺项，以后改成信封加密时，key 接口的 16 字节响应不变。 |
| `drama` `episode` `user_unlock` `episode_play_stat` | Demo | Core 的新表不要复制这些名字。`episode.asset_id` 是 Demo 指向 Core 的外键，拆库前保留。 |

资产状态：`UPLOADING → UPLOADED → TRANSCODING → READY`，失败为 `FAILED`。任务状态：`PENDING → RUNNING → SUCCEEDED`，中间可有 `RETRYING`，终态失败为 `FAILED`。Core 另有用户取消：任务和（非 `READY` 的）资产为 `CANCELLED`；DLQ 重放把 `FAILED` 的 Core 任务改回 `PENDING`、资产改回 `UPLOADED`。

## 明确不承诺

这些都能改，不需要保住兼容：

- `vod-hls` 桶策略允许匿名 `GetObject`。路线图要求私有桶加回源鉴权。
- `content_key.key_hex` 明文。
- 播放计数 `GETDEL` 之后再写 MySQL。进程在这两步之间崩溃会丢增量。
- 管理接口没有鉴权。
- 建剧默认 `ONLINE`，播放不检查剧的上下架。
- 没有租户、API Key、Outbox、Webhook、用量。
- `common.py` 在 api 和 worker 各有一份拷贝。除了 `transcode_message()` 必须一致，其他重复不构成公开契约。

## 换控制面时怎么做

1. 新接口走 `/api/v1`，不要改上面标了冻结的路径的状态码和字段。
2. 转码消息继续用 v1，直到 Worker 同时接受 v2。不要在一个版本里既改字段含义又要求消费者立刻升级。
3. Demo 的剧、集、解锁不要进 Core 的表。
4. 播放签名算法和 `/keys/{asset_id}` 的 16 字节响应，在 Nginx 和现有播放器替换完之前保持不变。
5. e2e 还是旧路径的回归测试。Demo 改调 `/api/v1` 时，再把 e2e 的准备数据从「建剧」换成「建资产」。在那之前不要为了新模型拆掉 e2e。
