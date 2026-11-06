# drama-vod：短剧点播 MVP（docker compose 一键起）

一套最小但"链路完整"的点播系统：**预签名直传 → MQ → FFmpeg 多码率 HLS → Nginx 模拟 CDN（secure_link 防盗链 + proxy_cache）→ hls.js 播放**，付费集走 **AES-128 HLS + 鉴权取 key**，Redis 做热点缓存 / 播放计数 / 热榜，MySQL 存元数据，Prometheus 拉指标。

演进方向见 [docs/panda-vod-roadmap.html](docs/panda-vod-roadmap.html)。现有接口里哪些不能改，见 [docs/phase-0-contract.md](docs/phase-0-contract.md)。

## 1. 目录结构

```
drama-vod/
├── docker-compose.yml            # 主编排：minio mysql redis rabbitmq api worker nginx (+prometheus, profile=obs)
├── docker-compose.noiptables.yml # 仅"Docker 没有 iptables"的环境需要（固定 IP + extra_hosts），正常机器不用
├── .env                          # 本地开发用的密码/密钥（生产换掉，别提交）
├── mysql/init.sql                # 表结构：drama episode video_asset asset_rendition transcode_job content_key user_unlock episode_play_stat
├── api/                          # FastAPI：建剧/集、发预签名 URL、complete 回调建任务、播放鉴权签 URL、key 服务、计数
│   ├── main.py  common.py  requirements.txt  Dockerfile
├── worker/                       # 转码 worker：抢任务(状态机) → ffprobe → ffmpeg 一条命令出 3 档 HLS → 上传 → 事务落库
│   ├── worker.py common.py requirements.txt Dockerfile (静态 ffmpeg 7.1)
├── nginx/
│   ├── templates/default.conf.template  # :8080 播放/API/key/HLS(secure_link+cache)；:9000 上传网关(只放行 PUT)
│   └── html/index.html                  # 播放页：手机全屏 / 电脑左播右选；?ep= 仍直接开播
├── prometheus/prometheus.yml
└── scripts/
    ├── e2e.py                    # 端到端验证（只用标准库 + 本机 ffmpeg）
    └── browser_check.py          # 可选：无头 Chrome 跑测试页
```

## 2. 启动

前提：Docker + compose 插件；跑 e2e 需要本机 `python3` 和 `ffmpeg`。

```bash
cd drama-vod
docker compose up -d --build            # 首次会构建 api / worker 镜像
docker compose ps                       # mysql 健康后 api/worker 会自动连上（有重试）
# 想看指标再加：docker compose --profile obs up -d prometheus
```

> 如果你的 Docker 守护进程是 `--iptables=false` 跑的（容器里按服务名解析失败），改用：
> `docker compose -f docker-compose.yml -f docker-compose.noiptables.yml up -d --build`
> 同时如果容器之间 ping 不通，检查 `sysctl net.bridge.bridge-nf-call-iptables`，设成 0。普通 Docker Desktop / Linux 安装不会遇到。

| 地址 | 用途 |
|---|---|
| http://localhost:8080 | 播放页（手机全屏、电脑左右分栏；`?ep=1&uid=7` 仍直接开播） |
| http://localhost:8080/api/... | API（经 nginx，带 X-Request-ID） |
| http://localhost:9000 | 上传网关（只接受预签名 PUT，转发 MinIO） |
| http://localhost:9001 | MinIO 控制台（minioadmin / minioadmin123） |
| http://localhost:15672 | RabbitMQ 管理台（guest / guest） |
| http://localhost:9090 | Prometheus（obs profile） |

播放页刷新即可更新（`nginx/html` 是挂载进去的）。剧列表和 iPhone 付费集播放要重新构建 api：`docker compose up -d --build api`。手机请用这台电脑的局域网地址，不要用 `localhost`。

## 3. 验证

```bash
python3 scripts/e2e.py        # 全部通过 exit 0；否则打印 FAIL 项并 exit 1
python3 scripts/browser_check.py 'http://localhost:8080/?ep=1&uid=7'   # 可选，需要 google-chrome + pip 的 websocket-client
```

e2e 覆盖：ffmpeg 生成 20s 竖屏视频 → 3 集（第 3 集付费）预签名 PUT 直传 → complete 回调（重复调用幂等、未上传 409）→ 等 READY → master MISS/HIT、换用户换签名仍 HIT → 首片 1s 后续 4s → 篡改签名/跨资产/改过期时间 403 → 付费集 402、解锁后可播、m3u8 带 AES-128 → key 无 token/错 token 401、合法 200 → 用 ffmpeg 经 nginx 完整解码免费集和加密集、无 token 解密失败 → Redis 计数回刷 MySQL。

### 手动走一遍（curl）

```bash
curl -s -XPOST localhost:8080/api/dramas -H 'content-type: application/json' -d '{"title":"测试剧"}'          # {"drama_id":1}
curl -s -XPOST localhost:8080/api/dramas/1/episodes -H 'content-type: application/json' -d '{"ep_no":1,"title":"第1集"}'
curl -s -XPOST localhost:8080/api/episodes/1/upload-url    # 拿 asset_id + put_url
curl -XPUT --data-binary @test.mp4 "<put_url>"             # 客户端直传对象存储，不经过 API
curl -s -XPOST localhost:8080/api/assets/<asset_id>/complete
curl -s localhost:8080/api/assets/<asset_id>               # 看 job 状态 PENDING→RUNNING→SUCCEEDED
curl -s 'localhost:8080/api/episodes/1/play?user_id=7'     # 拿签名播放地址
```

### 失败重试 / 死信

上传一个坏文件（如 `head -c 50000 /dev/urandom > bad.mp4`）走同样流程：worker 会按 10s、20s 退避重试，第 3 次失败标 FAILED 并进 `transcode.dlq`（RabbitMQ 管理台可见）。

## 4. 扩展

- 加 worker：`docker compose up -d --scale worker=3`（任务抢占靠 MySQL 条件 UPDATE + 租约，多实例安全）。注意 noiptables 覆盖文件给 worker 固定了 IP，那种模式下不能 scale。
- API 换 Spring Boot：接口、表结构、MQ 消息体、nginx 签名算法都不变，只替换 `api/` 服务即可（签名：`base64url(md5("$exp/$asset_id SECRET"))` 去掉 `=`）。

## 5. 已知缺口（MVP 有意不做）

- `vod-hls` 桶在内网匿名可读（只给 nginx 回源用）；生产应改成源站鉴权（私有桶 + 回源签名 / OAI）。
- 上传网关没处理浏览器跨域 CORS/OPTIONS，当前只适合脚本/App 直传。
- 内容密钥明文存 `content_key` 表；生产应 KMS 信封加密，且 key 服务独立部署。
- 没有真正的用户体系，`user_id` 由调用方传，play token 只是 HMAC 演示。
- 只做了 HLS + TS + H.264/AAC；没做 fMP4/CMAF、H.265、DRM（Widevine/FairPlay）、多 CDN 调度。
- 播放计数回刷是 30s 定时 + 手动接口，没有做精确一次。
