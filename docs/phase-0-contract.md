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

### Core（Phase 5 新增，上面的冻结与 Demo 条目不变）

鉴权分两类，互不通用：

- **租户接口**（`/api/v1/uploads`、`/assets`、`/jobs`、`/templates`、`/webhooks`、`/webhook-deliveries`、`/usage`、播放 token）：`X-Api-Key` 或 `Authorization: Bearer <key>`。key 无效、过期、撤销，或所属租户已停用：**401**。scope 不足：**403**。
- **管理接口** `/api/v1/admin/**`：`Authorization: Bearer vadm1.<HS256 JWT>`，由 `POST /api/v1/admin/login` 签发（`VOD_ADMIN_JWT_SECRET`，有效期是配置项）。缺失、伪造、过期，或管理用户已停用：**401**。租户 key 调管理接口一律 401，管理 token 调租户接口也一律 401。
- `/api/v1/healthz`、`/api/v1/qoe`、`/keys/v1/**` 的放行和鉴权方式不变。

| 接口 | 必须保持 |
|---|---|
| `POST /api/v1/assets/{id}/transcodes` | scope `asset`。body 可选 `{"template": 名称}`，缺省用 `ladder_v1`。资产不存在、属于别的租户、模板不可见：**404**。源文件还没上传完：**409**。同一资产有任一任务（**任意模板**）处于 `PENDING`/`RETRYING`/`RUNNING`：**409**，不新建行也不复用。处于 `SUCCEEDED`/`FAILED`/`CANCELLED`：**复用同一行**，改回 `PENDING`、`claim_version`+1，返回 **202** 和任务详情。 |
| `POST /api/v1/jobs/{id}/retry` | scope `job`。跨租户或不存在：**404**。只接受 `FAILED`；`CANCELLED` 是否也接受由 `vod.jobs.retry-allow-cancelled` 决定（默认接受）；其他状态 **409**。重置规则与 `scripts/dlq.py replay` 相同，以数据库为准。成功 **202**。 |
| 上面两个接口的 `Idempotency-Key` | 同一租户、同一接口、同一 key、同一目标的重放：**200**、同一任务、`idempotent_replay: true`，不再写 outbox。同一 key 换了目标：**409**。key 的有效期是配置项。 |
| 限流 | 租户接口按租户限流，管理登录按客户端 IP 限流。超限：**429**，带 `Retry-After`，body 里 `code: "rate_limited"`。 |
| 转码配额 | 同时未结束的任务数（`max_concurrent_jobs`）和每日新建、重转、重试的总次数（`max_daily_transcodes`）超限：**429**，body 里 `code: "quota_exceeded"`、`quota: "concurrent_jobs"\|"daily_transcodes"`。每日上限还带 `Retry-After`。存储配额仍是 Phase 2 的 **403**。 |
| `GET /api/v1/usage` | 今天的 `day`、`storage_bytes`、`play_requests`、`transcode_jobs` 这几个顶层字段不变。新增 `quota`（`null` = 不限）、`storage_used_bytes`、`concurrency`、`transcodes_today`；`?days=N` 时加 `history`。 |
| `POST /api/v1/webhooks` | URL 和解析结果都要通过 SSRF 检查，否则 **400**。`events` 只接受下面的事件名或 `*`，缺省订阅 succeeded/failed/cancelled。`secret` 只在这次响应里返回，列表、停用、投递记录都不返回。 |

API Key 明文只在管理端创建、轮换的响应里出现一次，库里只存 SHA-256。

**Webhook 事件（名称冻结）**：

| 事件 | 何时写入（同一事务） | payload 额外字段 |
|---|---|---|
| `transcode.requested` | complete 建任务、重新转码、重试的事务（vod-api） | `reason`：`upload` / `retranscode` / `retry` |
| `transcode.succeeded` | Worker 写 `SUCCEEDED` / `READY` / 前缀 `ACTIVE` 的成功事务 | — |
| `transcode.failed` | Worker 写最终 `FAILED` 的事务（中间的 `RETRYING` 不发） | `error_code` |
| `transcode.cancelled` | vod-api 的取消事务（已经是 `CANCELLED` 时重复调用不再发） | `reason: "cancelled"` |

取消用独立的 `transcode.cancelled`，不复用 `transcode.failed`。

- payload 是 JSON：`event_id`、`event_type`、`occurred_at`、`tenant_id`、`job_id`、`asset_id`、`claim_version`，以及上表的额外字段。可以加字段。
- 每次重试发送完全相同的字节。签名不变：`X-Vod-Signature: sha256=hex(hmac_sha256(secret, body))`。另带 `X-Vod-Event`、`X-Vod-Event-Id`、`X-Vod-Delivery-Id`、`X-Vod-Delivery-Attempt`。
- 至少一次投递：接收方用 `X-Vod-Event-Id` 去重，2xx 算成功。
- 非 2xx 或连接失败时，指数退避加 jitter 重试，到最大次数后 `FAILED`。端点已停用时 `SKIPPED`。重定向不跟随。次数和间隔都是配置项。
- 事件在 `vod_webhook_event`，**从不写进 `vod_outbox`**。

**SSRF**：登记和每次投递都要先解析，再要求每个地址都是公网单播（IPv4 内网/保留段；IPv6 只收 `2000::/3` 且排除文档、Teredo、6to4；IPv4 映射与 NAT64 按内嵌 IPv4 判断）。然后连接到这次检查过的 IP，用原主机名做 SNI、证书校验和 `Host`，不按主机名重新解析。dev 开关 `vod.webhook.dev-allow-http`、`dev-allow-private-hosts` 只在 `VOD_ENV=dev` 生效，非 dev 下设置会拒绝启动。

### Core 上传与资产（Phase 6 新增，上面的冻结与 Demo 条目不变）

租户接口，scope `upload`（资产接口 scope `asset`）。跨租户或不存在一律 **404**。

| 接口 | 必须保持 |
|---|---|
| `POST /api/v1/uploads` | body 可选 `mode`：缺省或 `"put"` 时响应字段与 Phase 2 完全相同（`session_id`、`asset_id`、`put_url`、`expires_in`、`expires_at`），不多不少。`"multipart"`：在源桶创建 S3 multipart，响应 `session_id`、`asset_id`、`mode: "multipart"`、`part_size_bytes`、`max_parts`、`expires_in`、`expires_at`，**没有** `put_url`。其他值 **400**。可选 `content_type`（只对 multipart 生效，必须在允许列表里，否则 **415**；缺省按扩展名推）。对象键仍是 `raw/t{tenant}/{asset}/source.{ext}`。`Idempotency-Key`：同一租户同一 key、OPEN 未过期时按**原会话的方式**重放（multipart 不会再建一次存储侧上传，与本次请求的 `mode` 无关）；已 COMPLETED / ABORTED 只返回会话信息，不签任何上传地址；OPEN 已过期 **409**。multipart 会话有效期单独配置，从 open 起算、不续期。 |
| `GET /api/v1/uploads/config` | `part_size_bytes`、`multipart_threshold_bytes`（客户端达到它就走 multipart）、`max_parts`、`max_parts_per_request`、`max_object_bytes`（`null` = 不限）、两种会话的有效期。客户端按它切片，不写死。 |
| `POST /api/v1/uploads/{id}/parts` | body `{"part_numbers":[1,2,…]}`（1..10000，去重排序，单次不超过 `max_parts_per_request`，否则 **400**）。返回 `parts:[{part_number,url}]`、`expires_in`（不超过会话剩余时间）、`session_expires_at`。put 会话 **409**；非 OPEN 或已过期 **409**。会话内可以重复签（URL 过期后重签同一 part）。 |
| `GET /api/v1/uploads/{id}/parts` | 存储侧已收到的分片 `parts:[{part_number,etag,size}]` 与 `part_size_bytes`，用于断点续传。状态规则同上。 |
| `POST /api/v1/uploads/{id}/complete` | put 会话：行为不变（先 HEAD 再校验再建任务）。multipart 会话：body 必须带 `{"parts":[{"part_number","etag"}]}`（缺失 **400**），**先**在存储侧组合，成功后**再**走同一条 HEAD + 大小/MIME/扩展名 + 配额 + 建任务/outbox 事务。etag 对不上、缺 part、顺序不对、非末块小于 5 MiB：**400**，会话保持 OPEN、不建任务，可改正后重试。上一次已组合但建任务失败（如配额）时重试：存储侧 upload 已不存在、桶里已有对象，继续 HEAD 校验；会话此时即使已过期（仍 OPEN）也照此继续，不返回 409。没组合过的过期会话仍 **409**。存储不可用 **503**。multipart 对象的 ETag 不是整文件 MD5，也可能没有 SHA-256：不因缺 checksum 拒绝。已建过任务时重复调用返回同一任务。分片信息不进消息 v1。 |
| `POST /api/v1/uploads/{id}/abort` | OPEN 会话（含已过期的）可取消：multipart 先在存储侧 abort，再记 `ABORTED`；重复调用 **200**。已 COMPLETED **409**。存储不可用 **503**，会话保持 OPEN。资产保持 `UPLOADING`（没有源文件，不能转码），不删除任何对象。 |
| 过期清理 | 后台按间隔领取（条件 UPDATE，可多实例）已过期仍 OPEN 的 **multipart** 会话：桶里已有目标对象（已组合、未建任务）就跳过、保持 OPEN 等客户端重试 complete；否则存储侧 abort 后记 `ABORTED`；失败只打日志，领取到期后再试。COMPLETED 不碰；put 会话不处理；不删除任何对象。 |
| `poster_url` | `GET /api/v1/assets`（每行）、`GET /api/v1/assets/{id}`、`POST .../playback-token`、`GET .../playback` 都带 `poster_url`。只有封面键落在资产**当前** `hls_prefix`（带 gen 的任务专属前缀）里才签发，地址形如 `/hls/v1/{sig}/{exp}/{asset}/{gen}/poster.jpg`，签名与该 gen 的 `master.m3u8` 相同；playback-token / playback 与 `master_url` 同一个 `exp`，列表与详情用 `play-ttl-seconds`，都不超过 `token-ttl-max-seconds`。没有封面为 `null`，不报错。签名地址会过期，**不写进 Webhook 事件体**；事件名与 payload 不变。 |
| 封面生成 | Worker 在 Core 转码成功时从源视频抽一帧 JPEG，写到当期前缀 `assets/v1/{asset}/{job}-{claim_version}/poster.jpg`，进本地清单并做远端 list 对账；`poster_key` 与 `hls_prefix` / `READY` / 前缀 `ACTIVE` 同一事务写入。抽帧失败不影响 `READY`，只是 `poster_key` 为 `NULL`。重新转码后封面跟新前缀走，旧封面随旧前缀由 GC 删除。旧 Demo 产物与 `content_key` 不变。 |

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

### Core（Phase 4 新增，上面的旧 Demo 条目不变）

Core 资产走 `/hls/v1/`，签名明文多一段 `v1`，避免和旧资产 id 的签名混用。两种形状：

```
# Phase 4 起新转码的资产：hls_prefix = assets/v1/{asset_id}/{gen}/，gen = {job_id}-{claim_version}（只含数字和一个 -）
明文 = "{exp}/v1/{asset_id}/{gen} {HLS_SECRET}"
URL  = /hls/v1/{签名}/{exp}/{asset_id}/{gen}/master.m3u8

# Phase 4 之前 READY 的资产：hls_prefix 仍是固定前缀 assets/v1/{asset_id}/，URL 不变
明文 = "{exp}/v1/{asset_id} {HLS_SECRET}"
URL  = /hls/v1/{签名}/{exp}/{asset_id}/master.m3u8
```

签名算法与旧 Demo 相同（md5 → base64url 去掉 `=`）。只有为某个 gen 签的地址能读该 gen 目录：资产级签名打到带 gen 的路径会命中带 gen 的 location，按含 gen 的明文校验，403。Nginx 里带 gen 的 location 必须排在不带 gen 的 `/hls/v1/` 正则之前。共享测试向量在 [contracts/hls-sign-vectors.json](contracts/hls-sign-vectors.json)，Java（`PlaybackSignVectorsTest`）与 `scripts/test_playback_contract.py`（代入 Nginx 模板里的 `secure_link_md5` 表达式）都核对它。

Core 播放 token 和旧 token 互不通用：

```
payload = base64url(无填充)( {"t": tenant_id, "a": asset_id, "exp": exp[, "v": viewer_id]} )   紧凑 JSON
token   = "vpt1." + payload + "." + base64url(无填充)( hmac_sha256(VOD_PLAY_TOKEN_SECRET, "vpt1." + payload) )
```

由 `POST /api/v1/assets/{id}/playback-token`（scope `asset`，跨租户/不存在 404，非 READY 409）签发，响应 `token`、`master_url`（与 token 同一个 `exp`）、`expires_at`、`encrypted`，`Cache-Control: no-store`。`viewer_id` 是调用方给的不透明串，只进日志，不入库，不参与授权。`GET /api/v1/assets/{id}/playback` 保留（只给 `master_url`、`expires_at`），与新接口共用签名逻辑。

- `GET /keys/v1/{asset_id}`（vod-api，不走 API Key）：`Authorization: Bearer` 或 `?token=` 带 Core token；token 无效/过期、asset 不一致、资产不属于 token 的租户、未加密、非 READY 都是 **401**，资产没有 key **404**，合法返回 **16 字节**；所有响应 `Cache-Control: no-store, private`。旧 token 在这里无效，Core token 在旧 `/keys/{asset_id}` 也无效。
- `POST /api/v1/qoe`：不走 API Key，body 里带 Core token；token 过期后在 `vod.qoe.token-grace-seconds` 内仍接受。
- 有效期、上限、宽限期都是配置项（`vod.playback.*`、`vod.qoe.*`），不冻结具体秒数。

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

### Core（Phase 4 新增，上面的旧 Demo 条目不变）

| 对象 | 位置 |
|---|---|
| HLS（Phase 4 起） | 桶 `vod-hls`，每次 claim 一个前缀 `assets/v1/{asset_id}/{job_id}-{claim_version}/`，下面的 master / 子列表 / 分片布局与旧 Demo 相同 |
| HLS（Phase 4 之前已 READY） | 固定前缀 `assets/v1/{asset_id}/`，不迁移、不清理 |
| 当前播放版本 | `vod_media_asset.hls_prefix`；只在带 `claim_version` 的成功事务里与 `READY`、前缀状态一起切换 |
| 加密 Key URI | `/keys/v1/{asset_id}`（Phase 4 之前已 READY 的 Core 加密资产仍是 `/keys/{asset_id}`，不迁移） |

- Worker 上传前在 `vod_output_prefix` 记 `WRITING`，master 最后传，传完按本地清单 list 远端核对名字和大小，一致才提交；成功事务把本前缀置 `ACTIVE`、同资产其他前缀置 `GARBAGE`。失败、取消、围栏丢失时本前缀置 `GARBAGE`。
- Worker 的清理线程只删 `GARBAGE` 且过了 `WORKER_OUTPUT_GC_GRACE_SECONDS` 的任务专属前缀，删前再确认它不是资产当前 `hls_prefix`；删完置 `DELETED`。固定前缀与旧 Demo 前缀永远不删。
- 边缘：`/hls/v1/{sig}/{exp}/{asset}/{gen}/{file}` 回源 `/internal/origin/v1/{asset}/{gen}/{file}`（对象 `assets/v1/{asset}/{gen}/{file}`），缓存键 `v1/{asset}/{gen}/{file}`；不带 gen 的 `/hls/v1/` 仍是 `v1/{asset}/{file}`。只有 Core 路由给客户端 `Cache-Control`（gen 路径 `public, max-age=…, immutable`；固定前缀 m3u8 短、分片长；403/410 等 `no-store`）和 CORS；旧 `/hls/` 不加这些头。

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

Phase 4 已对 Core 落地：任务专属前缀见「对象与 HLS」的 Core 小节；旧 Demo 仍是固定前缀（冻结）。Core 写 `TRANSCODING` 改为显式事务、先 `SELECT … FOR UPDATE` 锁任务行再改资产，和取消事务同序。

Phase 5（Core）：

- **重新转码与重试复用 `(asset_id, profile)` 这一行**，不拆 `uk_asset_profile`。profile 就是模板名（系统默认 `ladder_v1`）。Worker 按 `job_id → template_id`（为空时按 profile 找系统模板）读梯队，消息 v1 不变。默认模板的参数与上面冻结的 720/540/360 一致。
- 重置时 `claim_version`+1，Worker 领取时再 +1，并按 Phase 4 规则插入新的 `WRITING` 前缀。
- 成功事务切换前缀。在此之前已 `READY` 的 Core 资产保持 `READY`，旧前缀继续可播；Worker 的 `TRANSCODING`/`FAILED` 不再把 `READY` 的 Core 资产降级（旧 Demo 不变）。
- 加密资产复用已有的 `vod_content_key`。
- 成功、最终失败事件与任务终态同一事务写入 `vod_webhook_event`。围栏丢失时整个事务回滚，不留事件。

## 表归谁

| 表 | 归属 | Phase 0 决定 |
|---|---|---|
| `video_asset` `asset_rendition` `transcode_job` `content_key` | Core | 列可以加，不能改唯一键 `(asset_id, profile)` 和 `(asset_id, name)`。`content_key` 明文是不承诺项，以后改成信封加密时，key 接口的 16 字节响应不变。 |
| `drama` `episode` `user_unlock` `episode_play_stat` | Demo | Core 的新表不要复制这些名字。`episode.asset_id` 是 Demo 指向 Core 的外键，拆库前保留。 |
| `vod_content_key`（V10） `vod_output_prefix`（V11） | Core（`vod_*`） | Phase 4 新增，只进 Flyway，不进 `mysql/init.sql`。`vod_content_key` 以 `vod_media_asset.id` 为主键，和旧 `content_key`（`video_asset.id`）分开，两套自增 id 不再撞号；明文是不承诺项，key 接口的 16 字节响应不变。`vod_output_prefix` 记录 Core 输出前缀生命周期 `WRITING/ACTIVE/GARBAGE/DELETED`。 |
| `vod_admin_user` `vod_transcode_template` `vod_idempotency_key` `vod_webhook_event`（V12） | Core（`vod_*`） | Phase 5 新增，只进 Flyway。`vod_tenant` 加 `max_concurrent_jobs`/`max_daily_transcodes`，`vod_api_credential` 加 `name`/`key_prefix`/`revoked_at`，`vod_transcode_job` 加 `template_id`，`vod_webhook_delivery` 加投递状态列（旧行 `event_id` 为空，不会被投递循环领取）。 |

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

## 路线 C：Demo 拆成视频云的客户（2026-11-19）

Phase 0 写过：「Demo 改完之前，`play?user_id=` 不能从现有进程里拿掉」「e2e 在 Demo 拆出去之后要改成先建资产、再由 Demo 关联」。路线 C 就是这一步。上面的冻结条目按下表变化，没列出的不变。旧链路的读接口（`/hls/{sig}/…`、`/keys/{id}`、`/api/playback/{asset}/…`）保留，只为切换前已经发出去的地址服务，新的播放不再产生它们。

| 接口 | 路线 C 之后 |
|---|---|
| `POST /api/episodes/{id}/upload-url` | 改为在 Core 开 put 上传会话：`asset_id` 是 **Core 资产 id**，`put_url` 指向 Core 的源对象键 `raw/t{租户}/{资产}/source.{ext}`（不再是 `raw/{剧}/{集}/{资产}/source.mp4`），`expires_in` 是 Core 会话有效期（默认 900）。付费集上传成 Core 加密资产。 |
| `PUT` 预签名 URL | 仍是直传对象存储。Core complete 会校验源文件类型，**直传要带 `Content-Type`**（如 `video/mp4`；浏览器用 `File` 上传会自动带上）。 |
| `POST /api/assets/{id}/complete` | 转给 Core：对象不存在仍 **409**，重复调用仍返回同一个 `job_id`。只认路线 C 之后开的上传（旧资产 404）。 |
| `GET /api/assets/{id}` | Core 资产从视频云读：`asset.status`、`jobs[]` 不变，`renditions[]` 恒为空数组（Core 不按档位记）。旧资产仍从旧表读。 |
| `GET /api/episodes/{id}/play` | 观众来自 Demo 会话（`Authorization: Bearer dsess1.…`，由 `/api/auth/register`、`/api/auth/login` 签发）。`?user_id=` 只在和会话一致时允许（不一致 **403**）；没有会话时不再按它认人（过渡开关 `DEMO_ALLOW_USER_ID_PARAM=1` 可恢复旧行为）。匿名看付费集 **401**（新增）；未解锁 **402**、未就绪 **409** 不变。地址和 token 由视频云签：`master_url` 是 `/hls/v1/…`，`token` 是 Core 播放 token（`vpt1.`），加密集的 key 走 `/keys/v1/{id}`；新增 `native_master_url`（原生 HLS 用）、`poster_url`、`via`。`encrypted` 取 Core 资产的真实值。还没迁到 Core 的旧集暂时仍走旧链路（`via: "legacy"`）。 |
| `POST /api/users/{id}/unlock` | 要本人的会话（没有 **401**、别人 **403**）。付费集按 `price_coins` 扣金币，余额不够 **402** 且 `code: "insufficient_coins"`；重复调用仍 200，不重复扣（`charged: 0`）。 |
| `/api/playback/{asset}/master.m3u8?token=` | 只服务旧资产。Core 资产的原生 HLS 入口是 vod-api 的 `GET /api/v1/native-hls/{asset}/master.m3u8?token=<Core 播放 token>`：分片仍落在 `/hls/v1/` 签名地址下（与 `master_url` 同一签名、同一缓存规则），key URI 带 `?token=`。token 无效、过期、资产不匹配或资产不是 READY 一律 **401**，响应 no-store。 |

转码：旧 Demo 的转码链路（`transcode_job`、`assets/{id}/`、`/keys/{id}`）停用，Worker 默认确认并丢弃旧消息（`WORKER_LEGACY_ENABLED=1` 可临时打开以排空遗留任务）。消息 v1 本身不变，Core 仍用它。已有的旧剧集用 `api/migrate_core.py` 迁到 Core。
