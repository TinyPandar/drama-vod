# drama-vod：短剧点播示例

Docker Compose 点播系统，包含两条并行演进的路径：旧版 Drama Demo（FastAPI）和新版 VOD Core 控制面（Java 21 + Spring Boot）。播放页仍使用 Demo；管理控制台调用 `/api/v1`；两条路径共享 Python 转码 Worker。

媒体流程：**预签名直传 → RabbitMQ → FFmpeg 多码率 HLS → Nginx 签名校验与缓存 → hls.js 播放**。HLS 桶保持私有，API 验证回源密钥后为 Nginx 提供对象。旧 Demo 的付费集使用 AES-128 HLS。MySQL 保存数据，Redis 提供热点缓存和播放计数，Prometheus 是可选监控。

路线图见 [docs/panda-vod-roadmap.html](docs/panda-vod-roadmap.html)，旧接口兼容约定见 [docs/phase-0-contract.md](docs/phase-0-contract.md)。

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
