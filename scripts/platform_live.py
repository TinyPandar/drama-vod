#!/usr/bin/env python3
"""Phase 5 平台化验收（需要 docker compose 栈、本机 ffmpeg；PyMySQL 可选，用于核对旧前缀进入清理）。在仓库根目录运行：

  # 管理用户来自 VOD_ADMIN_USERNAME / VOD_ADMIN_PASSWORD（起栈前写进 .env 或环境变量，仓库里没有默认口令）
  docker compose -p drama-vod-p5 -f docker-compose.yml -f docker-compose.platform-live.yml up -d --build
  COMPOSE_PROJECT_NAME=drama-vod-p5 python3 scripts/platform_live.py

覆盖：管理登录；伪造/过期管理 JWT 401；租户 Key 调 /admin/** 401、管理 JWT 调租户上传 401；创建两个租户与 Key（明文只出现一次）；
SSRF 地址登记被拒；上传 → READY → 收到签名正确的 transcode.succeeded；跨租户资产 404；重新转码 claim_version 增加、
播放前缀切换、旧前缀 GARBAGE（有 PyMySQL 时核对）；坏源文件 FAILED → transcode.failed → 重试 claim_version+1；
每日转码配额超限 429；停用租户后 Key 401。

回调接收端：默认在本机起 HTTP 服务，回调地址 http://host.docker.internal:端口/hook，需要上面的叠加文件打开 dev 开关。
也可以设 PLATFORM_LIVE_HOOK_URL 指向一个公网 HTTPS 接收端，那时只按投递记录判断（拿不到请求体，无法核对签名）。
只报告真实跑出的结果，不做性能结论。
"""
import base64, hashlib, hmac, json, os, pathlib, socket, subprocess, sys, tempfile, threading, time
import urllib.error, urllib.request, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]


def dotenv(name, default=""):
    if os.environ.get(name):
        return os.environ[name]
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    return default


BASE = os.environ.get("VOD_BASE") or f"http://localhost:{dotenv('HTTP_PORT') or 8080}"
HOOK_PORT = int(os.environ.get("PLATFORM_LIVE_HOOK_PORT", "18099"))
HOOK_URL = os.environ.get("PLATFORM_LIVE_HOOK_URL") or f"http://host.docker.internal:{HOOK_PORT}/hook"
LOCAL_HOOK = not os.environ.get("PLATFORM_LIVE_HOOK_URL")
TIMEOUT = int(os.environ.get("PLATFORM_LIVE_TIMEOUT", "300"))
results = []
received = []  # (headers, body bytes)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def http(method, path, body=None, headers=None, key=None, token=None, data=None, timeout=60):
    url = path if path.startswith("http") else BASE + path
    headers = dict(headers or {})
    if key:
        headers["X-Api-Key"] = key
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def js(resp):
    try:
        return json.loads(resp[2] or b"{}")
    except ValueError:
        return {}


class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        received.append((dict(self.headers), body))
        self.send_response(204)
        self.end_headers()

    def log_message(self, *a):
        pass


def wait_for(fn, timeout, step=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = fn()
        if out:
            return out
        time.sleep(step)
    return fn()


def event(event_type, job_id):
    for headers, body in received:
        try:
            payload = json.loads(body)
        except ValueError:
            continue
        if payload.get("event_type") == event_type and payload.get("job_id") == job_id:
            return headers, body, payload
    return None


def delivered(key, job_id, event_type):
    rows = js(http("GET", f"/api/v1/webhook-deliveries?job_id={job_id}", key=key)).get("deliveries", [])
    return next((d for d in rows if d["event_type"] == event_type and d["status"] == "DELIVERED"), None)


def forge_admin_token(secret, exp_offset):
    """按 AdminJwt 的格式签一个 token（用来造过期或伪造的 token）。"""
    b64 = lambda raw: base64.urlsafe_b64encode(raw).rstrip(b"=").decode()
    now = int(time.time())
    head = b64(json.dumps({"alg": "HS256", "typ": "JWT", "kid": "v1"}, separators=(",", ":")).encode())
    claims = b64(json.dumps({"iss": "vod-api", "aud": "vod-admin", "sub": "ops", "uid": 1, "iat": now - 7200,
                             "exp": now + exp_offset}, separators=(",", ":")).encode())
    sig = b64(hmac.new(secret.encode(), f"{head}.{claims}".encode(), hashlib.sha256).digest())
    return f"vadm1.{head}.{claims}.{sig}"


def upload(key, src, filename="live.mp4"):
    st, _, raw = http("POST", "/api/v1/uploads", {"filename": filename}, key=key)
    opened = json.loads(raw)
    assert st == 200, (st, opened)
    put = urllib.request.Request(opened["put_url"], data=open(src, "rb").read(), method="PUT",
                                 headers={"Content-Type": "video/mp4"})
    with urllib.request.urlopen(put, timeout=120) as resp:
        assert resp.status in (200, 204), resp.status
    st, _, raw = http("POST", f"/api/v1/uploads/{opened['session_id']}/complete", key=key)
    assert st == 200, (st, raw[:200])
    return opened["asset_id"], json.loads(raw)["job_id"]


def job(key, job_id):
    return js(http("GET", f"/api/v1/jobs/{job_id}", key=key))


def finished(key, job_id, version=None):
    def probe():
        j = job(key, job_id)
        if j.get("status") in ("SUCCEEDED", "FAILED", "CANCELLED") and (version is None or j.get("claim_version", 0) >= version):
            return j
        return None
    return wait_for(probe, TIMEOUT, 3) or job(key, job_id)


def prefix_status(prefix):
    try:
        import pymysql
    except ImportError:
        return None
    try:
        conn = pymysql.connect(host=os.environ.get("MYSQL_HOST", "127.0.0.1"), port=int(dotenv("MYSQL_PORT") or 3306),
                               user=os.environ.get("MYSQL_USER", "vod"), password=dotenv("MYSQL_PASSWORD"),
                               database=os.environ.get("MYSQL_DB", "vod"), autocommit=True)
        with conn.cursor() as c:
            c.execute("SELECT status FROM vod_output_prefix WHERE prefix=%s", (prefix,))
            row = c.fetchone()
        conn.close()
        return row[0] if row else "MISSING"
    except Exception as e:  # 端口没映射、口令不对：跳过这一项
        return f"skip: {e.__class__.__name__}"


def main():
    st, _, _ = http("GET", "/api/v1/healthz")
    if st != 200:
        raise SystemExit(f"vod-api not reachable at {BASE} ({st}); start the compose stack first")
    user, password = dotenv("VOD_ADMIN_USERNAME"), dotenv("VOD_ADMIN_PASSWORD")
    if not user or not password:
        raise SystemExit("set VOD_ADMIN_USERNAME / VOD_ADMIN_PASSWORD (same values the stack was started with)")
    if LOCAL_HOOK:
        server = ThreadingHTTPServer(("0.0.0.0", HOOK_PORT), Hook)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src.mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "6",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", src], check=True)
    bad = os.path.join(tmp, "bad.mp4")
    with open(bad, "wb") as f:
        f.write(os.urandom(4096))  # 不是视频：ffprobe 失败 → INPUT_INVALID（不可重试）→ FAILED

    print("== 1. 管理端鉴权与隔离", flush=True)
    resp = http("POST", "/api/v1/admin/login", {"username": user, "password": password})
    admin = js(resp).get("token", "")
    check("管理登录拿到 vadm1. token", resp[0] == 200 and admin.startswith("vadm1."), resp[0])
    check("错误口令 401", http("POST", "/api/v1/admin/login", {"username": user, "password": password + "x"})[0] == 401)
    jwt_secret = dotenv("VOD_ADMIN_JWT_SECRET")
    if jwt_secret:
        check("过期的管理 JWT 401", http("GET", "/api/v1/admin/tenants", token=forge_admin_token(jwt_secret, -60))[0] == 401)
    else:  # dev 下未配置时服务端每次启动随机生成，脚本拿不到，没法用真密钥签一个过期 token
        print("SKIP 过期的管理 JWT 401  (未配置 VOD_ADMIN_JWT_SECRET)", flush=True)
    check("用别的密钥伪造的管理 JWT 401",
          http("GET", "/api/v1/admin/tenants", token=forge_admin_token(uuid.uuid4().hex * 2, 600))[0] == 401)
    check("没有前缀的裸 JWT 401", http("GET", "/api/v1/admin/tenants", token=admin[len("vadm1."):])[0] == 401)

    suffix = uuid.uuid4().hex[:6]
    tenants, keys = {}, {}
    for name in ("a", "b"):
        r = http("POST", "/api/v1/admin/tenants", {"name": f"p5-live-{name}-{suffix}"}, token=admin)
        tenants[name] = js(r).get("id")
        r = http("POST", f"/api/v1/admin/tenants/{tenants[name]}/keys", {"name": "live", "scopes": ["upload", "asset", "job"]},
                 token=admin)
        keys[name] = js(r).get("api_key", "")
    check("创建两个租户与 Key", all(tenants.values()) and all(k.startswith("vk_") for k in keys.values()), tenants)
    listing = http("GET", f"/api/v1/admin/tenants/{tenants['a']}/keys", token=admin)[2].decode()
    check("Key 列表不含明文", keys["a"] not in listing and "api_key" not in listing)
    check("租户 Key 调 /admin/** 401", http("GET", "/api/v1/admin/tenants", key=keys["a"])[0] == 401
          and http("GET", "/api/v1/admin/tenants", token=keys["a"])[0] == 401)
    check("管理 JWT 调租户上传 401", http("POST", "/api/v1/uploads", {}, token=admin)[0] == 401)

    print("== 2. Webhook 登记与 SSRF", flush=True)
    for url in ("https://169.254.169.254/latest/meta-data", "https://127.0.0.1/hook", "https://[::1]/hook",
                "https://localhost/hook", "https://10.0.0.1/hook"):
        check(f"SSRF 地址登记被拒 {url}", http("POST", "/api/v1/webhooks", {"url": url}, key=keys["a"])[0] == 400)
    r = http("POST", "/api/v1/webhooks", {"url": HOOK_URL, "events": "*"}, key=keys["a"])
    hook = js(r)
    secret = hook.get("secret", "")
    if not check("登记回调地址", r[0] == 200 and secret, f"{r[0]} {r[2][:160]!r}"):
        print("   提示：本地接收端需要 docker-compose.platform-live.yml 打开的 dev 开关", flush=True)
    check("Webhook 列表不返回 secret", secret not in http("GET", "/api/v1/webhooks", key=keys["a"])[2].decode())

    print("== 3. 上传 → READY → transcode.succeeded", flush=True)
    asset_id, job_id = upload(keys["a"], src)
    j = finished(keys["a"], job_id)
    check("首次转码成功", j.get("status") == "SUCCEEDED", j.get("status"))
    asset = js(http("GET", f"/api/v1/assets/{asset_id}", key=keys["a"]))
    first_prefix = asset.get("hls_prefix")
    check("资产 READY", asset.get("status") == "READY", asset.get("status"))
    if LOCAL_HOOK:
        got = wait_for(lambda: event("transcode.succeeded", job_id), 60)
        if check("收到 transcode.succeeded 回调", got):
            headers, body, payload = got
            expect = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
            check("X-Vod-Signature 校验通过", hmac.compare_digest(headers.get("X-Vod-Signature", ""), expect))
            check("payload 含 event_id/occurred_at/asset_id/claim_version",
                  all(k in payload for k in ("event_id", "occurred_at", "asset_id", "claim_version")) and payload["asset_id"] == asset_id)
    check("投递记录 DELIVERED", wait_for(lambda: delivered(keys["a"], job_id, "transcode.succeeded"), 60))

    print("== 4. 跨租户不可见", flush=True)
    check("租户 B 读租户 A 的资产 404", http("GET", f"/api/v1/assets/{asset_id}", key=keys["b"])[0] == 404)
    check("租户 B 给 A 的资产签播放 token 404", http("POST", f"/api/v1/assets/{asset_id}/playback-token", {}, key=keys["b"])[0] == 404)
    check("租户 B 重新转码 A 的资产 404", http("POST", f"/api/v1/assets/{asset_id}/transcodes", {}, key=keys["b"])[0] == 404)
    check("租户 B 重试 A 的任务 404", http("POST", f"/api/v1/jobs/{job_id}/retry", key=keys["b"])[0] == 404)

    print("== 5. 重新转码：同一行、claim_version 增加、前缀切换", flush=True)
    before = job(keys["a"], job_id)["claim_version"]
    r = http("POST", f"/api/v1/assets/{asset_id}/transcodes", {"template": "ladder_v1"}, key=keys["a"],
             headers={"Idempotency-Key": f"live-{suffix}"})
    re1 = js(r)
    # 重置时 claim_version +1；Worker 可能在响应读回任务之前就已领取（再 +1），两种都算对
    check("重新转码 202，同一任务 id，claim_version 已增加", r[0] == 202 and re1.get("id") == job_id
          and re1.get("claim_version") in (before + 1, before + 2),
          f"{r[0]} id={re1.get('id')} vs {job_id} cv={re1.get('claim_version')} vs {before}")
    replay = http("POST", f"/api/v1/assets/{asset_id}/transcodes", {"template": "ladder_v1"}, key=keys["a"],
                  headers={"Idempotency-Key": f"live-{suffix}"})
    check("同一 Idempotency-Key 重放 200", replay[0] == 200 and js(replay).get("idempotent_replay") is True, replay[0])
    check("排队/运行中再次发起 409", http("POST", f"/api/v1/assets/{asset_id}/transcodes", {}, key=keys["a"])[0] == 409)
    j = finished(keys["a"], job_id, before + 2)
    asset = js(http("GET", f"/api/v1/assets/{asset_id}", key=keys["a"]))
    check("重新转码成功、资产仍 READY", j.get("status") == "SUCCEEDED" and asset.get("status") == "READY",
          f"{j.get('status')} {asset.get('status')}")
    check("播放前缀换成新的任务专属前缀", asset.get("hls_prefix") not in (None, first_prefix),
          f"{first_prefix} -> {asset.get('hls_prefix')}")
    st = prefix_status(first_prefix)
    if st is None or str(st).startswith("skip"):
        print(f"SKIP 旧前缀状态核对（{st or '没有 PyMySQL'}）", flush=True)
    else:
        check("旧前缀进入清理（GARBAGE/DELETED）", st in ("GARBAGE", "DELETED"), st)
    tok = http("POST", f"/api/v1/assets/{asset_id}/playback-token", {}, key=keys["a"])
    master = js(tok).get("master_url", "")
    check("新版本可签播放地址并取到 master", tok[0] == 200 and http("GET", master)[0] == 200, master)

    print("== 6. 失败任务可重试", flush=True)
    bad_asset, bad_job = upload(keys["a"], bad)
    j = finished(keys["a"], bad_job)
    check("坏源文件最终 FAILED", j.get("status") == "FAILED", f"{j.get('status')} {j.get('error_code')}")
    if LOCAL_HOOK:
        check("收到 transcode.failed 回调", wait_for(lambda: event("transcode.failed", bad_job), 60))
    v = j.get("claim_version", 0)
    r = http("POST", f"/api/v1/jobs/{bad_job}/retry", key=keys["a"])
    # 重置后回到 PENDING、claim_version+1；Worker 可能在响应读回前就已领取（RUNNING、再 +1），两种都算对
    check("重试 202、回到 PENDING（或已被领取）、claim_version 增加", r[0] == 202
          and (js(r).get("status"), js(r).get("claim_version")) in (("PENDING", v + 1), ("RUNNING", v + 2)),
          f"{r[0]} {r[2][:120]!r}")
    check("SUCCEEDED 的任务重试 409", http("POST", f"/api/v1/jobs/{job_id}/retry", key=keys["a"])[0] == 409)

    print("== 7. 配额与停用", flush=True)
    usage = js(http("GET", "/api/v1/usage?days=3", key=keys["a"]))
    check("用量返回配额、并发、历史", "quota" in usage and "concurrency" in usage and len(usage.get("history", [])) == 3)
    http("PATCH", f"/api/v1/admin/tenants/{tenants['a']}", {"max_daily_transcodes": usage.get("transcodes_today", 1)}, token=admin)
    finished(keys["a"], bad_job, v + 2)
    r = http("POST", f"/api/v1/assets/{asset_id}/transcodes", {}, key=keys["a"])
    check("每日转码配额超限 429 quota_exceeded + Retry-After",
          r[0] == 429 and js(r).get("code") == "quota_exceeded" and r[1].get("Retry-After"), f"{r[0]} {r[2][:120]!r}")
    http("PATCH", f"/api/v1/admin/tenants/{tenants['b']}", {"status": "DISABLED"}, token=admin)
    check("停用租户后它的 Key 401", http("GET", "/api/v1/assets", key=keys["b"])[0] == 401)

    ok = sum(1 for _, x in results if x)
    print(f"\n== 结果：{ok}/{len(results)} 通过")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
