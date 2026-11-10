#!/usr/bin/env python3
"""Phase 4 Core 播放链路验收（需要 docker compose 栈已起好、本机 ffmpeg、PyMySQL）。在仓库根目录运行：

  python3 scripts/playback_live.py
  COMPOSE_PROJECT_NAME=drama-vod-p4 HTTP_PORT=18080 UPLOAD_PORT=19000 MYSQL_PORT=13306 python3 scripts/playback_live.py

覆盖：上传 → READY → POST playback-token → 经 Nginx 拉 master/子列表/分片（MISS→HIT、immutable、Range 206、CORS 预检）；
改签名 403、过期 410（都 no-store）；伪造/过期/别的资产的 token 取 key 401；跨租户签 token 404；
容器内匿名直读 MinIO HLS 对象被拒、上传网关不放行 GET；加密资产 /keys/v1/ 取到 16 字节并用 ffmpeg 解出一帧；
QoE 上报 204 且指标出现在 vod-api 的 /actuator/prometheus。只报告真实跑出的结果，不做性能结论。
"""
import hashlib, json, os, pathlib, re, subprocess, sys, tempfile, time, urllib.error, urllib.request, uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]


def dotenv(name):
    if os.environ.get(name):
        return os.environ[name]
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


BASE = os.environ.get("VOD_BASE") or f"http://localhost:{dotenv('HTTP_PORT') or 8080}"
UPLOAD = os.environ.get("VOD_UPLOAD_BASE") or f"http://localhost:{dotenv('UPLOAD_PORT') or 9000}"
KEY = os.environ.get("VOD_API_KEY") or dotenv("VOD_DEV_API_KEY") or "dev-demo-key"
ORIGIN = os.environ.get("VOD_CORS_TEST_ORIGIN", "http://localhost:8080")
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def http(method, path, body=None, headers=None, key=KEY, data=None, timeout=60):
    url = path if path.startswith("http") else BASE + path
    headers = dict(headers or {})
    if key:
        headers["X-Api-Key"] = key
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


def compose_exec(service, script):
    p = subprocess.run(["docker", "compose", "exec", "-T", service, "python", "-"], cwd=ROOT, input=script,
                       capture_output=True, text=True)
    return p.returncode, p.stdout.strip(), p.stderr.strip()[-300:]


def db():
    import pymysql
    return pymysql.connect(host=os.environ.get("MYSQL_HOST", "127.0.0.1"), port=int(dotenv("MYSQL_PORT") or 3306),
                           user=os.environ.get("MYSQL_USER", "vod"), password=dotenv("MYSQL_PASSWORD"),
                           database=os.environ.get("MYSQL_DB", "vod"), autocommit=True,
                           cursorclass=pymysql.cursors.DictCursor)


def upload(src, encrypted):
    st, _, raw = http("POST", "/api/v1/uploads", {"encrypted": encrypted, "filename": "live.mp4"})
    opened = json.loads(raw)
    assert st == 200, (st, opened)
    put = urllib.request.Request(opened["put_url"], data=open(src, "rb").read(), method="PUT",
                                 headers={"Content-Type": "video/mp4"})
    with urllib.request.urlopen(put, timeout=120) as resp:
        assert resp.status in (200, 204), resp.status
    st, _, raw = http("POST", f"/api/v1/uploads/{opened['session_id']}/complete")
    assert st == 200, (st, raw[:200])
    return opened["asset_id"]


def wait_ready(asset_id, timeout):
    deadline = time.time() + timeout
    while time.time() < deadline:
        asset = js(http("GET", f"/api/v1/assets/{asset_id}"))
        if asset.get("status") in ("READY", "FAILED", "CANCELLED"):
            return asset
        time.sleep(3)
    return js(http("GET", f"/api/v1/assets/{asset_id}"))


def token_for(asset_id, ttl=None, key=KEY):
    return http("POST", f"/api/v1/assets/{asset_id}/playback-token", {} if ttl is None else {"ttl_seconds": ttl}, key=key)


def first_uri(text):
    return next(l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#"))


def main():
    st, _, _ = http("GET", "/api/v1/healthz", key=None)
    if st != 200:
        raise SystemExit(f"vod-api not reachable at {BASE} ({st}); start the compose stack first")
    tmp = tempfile.mkdtemp()
    src = os.path.join(tmp, "src.mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "8",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", src], check=True)

    print("== 1. 上传明文与加密 Core 资产，等 READY", flush=True)
    plain_id, enc_id = upload(src, False), upload(src, True)
    plain, enc = wait_ready(plain_id, 240), wait_ready(enc_id, 240)
    check("两个资产都 READY", plain.get("status") == "READY" and enc.get("status") == "READY",
          f"{plain.get('status')} {enc.get('status')}")
    gen_re = re.compile(rf"^assets/v1/{plain_id}/(\d+-\d+)/$")
    m = gen_re.match(plain.get("hls_prefix") or "")
    check("hls_prefix 是任务专属前缀 assets/v1/{asset}/{job}-{claim}/", bool(m), plain.get("hls_prefix"))
    gen = m.group(1) if m else ""

    print("== 2. playback-token 与经 Nginx 拉流", flush=True)
    resp = token_for(plain_id)
    tok = js(resp)
    check("playback-token 200 + no-store", resp[0] == 200 and "no-store" in (resp[1].get("Cache-Control") or ""),
          f"{resp[0]} {resp[1].get('Cache-Control')}")
    check("响应含 token/master_url/expires_at/encrypted", tok.get("token", "").startswith("vpt1.")
          and tok.get("encrypted") is False and isinstance(tok.get("expires_at"), int))
    master_url = tok.get("master_url", "")
    check("master_url 带 gen", master_url.endswith(f"/{plain_id}/{gen}/master.m3u8"), master_url)
    st, h1, master = http("GET", master_url, key=None, headers={"Origin": ORIGIN})
    st2, h2, _ = http("GET", master_url, key=None)
    check("master MISS → HIT", st == 200 and h1.get("X-Cache-Status") in ("MISS", "EXPIRED") and h2.get("X-Cache-Status") == "HIT",
          f"{st} {h1.get('X-Cache-Status')} → {h2.get('X-Cache-Status')}")
    check("gen 路径 Cache-Control public immutable", "immutable" in (h1.get("Cache-Control") or ""), h1.get("Cache-Control"))
    check("CORS 允许的 Origin 被回显", h1.get("Access-Control-Allow-Origin") == ORIGIN, h1.get("Access-Control-Allow-Origin"))
    base = master_url.rsplit("/", 1)[0] + "/"
    variant = first_uri(master.decode())
    st, _, media = http("GET", base + variant, key=None)
    check("子列表 200", st == 200)
    seg = base + variant.rsplit("/", 1)[0] + "/" + first_uri(media.decode())
    st, _, _ = http("GET", seg, key=None)
    st_r, h_r, body_r = http("GET", seg, key=None, headers={"Range": "bytes=0-99"})
    check("分片 200，缓存命中后 Range 206", st == 200 and st_r == 206 and len(body_r) == 100,
          f"{st} {st_r} {h_r.get('X-Cache-Status')} {h_r.get('Content-Range')}")
    st, h, _ = http("OPTIONS", master_url, key=None, headers={"Origin": ORIGIN, "Access-Control-Request-Method": "GET"})
    check("预检 OPTIONS 204 带 CORS", st == 204 and h.get("Access-Control-Allow-Origin") == ORIGIN, f"{st}")
    sig = master_url.split("/")[3]
    st, h, _ = http("GET", master_url.replace(sig, ("A" if sig[0] != "A" else "B") + sig[1:]), key=None)
    check("改签名 403 + no-store", st == 403 and h.get("Cache-Control") == "no-store", f"{st} {h.get('Cache-Control')}")
    short = js(token_for(plain_id, ttl=2))
    time.sleep(3.5)
    st, h, _ = http("GET", short.get("master_url", "/x"), key=None)
    check("过期 410 + no-store", st == 410 and h.get("Cache-Control") == "no-store", f"{st} {h.get('Cache-Control')}")
    legacy_get = js(http("GET", f"/api/v1/assets/{plain_id}/playback"))
    check("GET .../playback 共用签名逻辑（同样带 gen）", legacy_get.get("master_url", "").endswith(f"/{gen}/master.m3u8"),
          legacy_get.get("master_url"))

    print("== 3. 加密资产与 /keys/v1/", flush=True)
    etok = js(token_for(enc_id))
    ebase = etok["master_url"].rsplit("/", 1)[0] + "/"
    evariant = first_uri(http("GET", etok["master_url"], key=None)[2].decode())
    emedia = http("GET", ebase + evariant, key=None)[2].decode()
    keyline = next((l for l in emedia.splitlines() if l.startswith("#EXT-X-KEY")), "")
    key_uri = (re.search(r'URI="([^"]+)"', keyline) or [None, ""])[1]
    check("Core 加密资产 Key URI 是 /keys/v1/{id}", key_uri == f"/keys/v1/{enc_id}", keyline)
    check("加密资产 token 响应 encrypted=true", etok.get("encrypted") is True)
    st, h, _ = http("GET", key_uri, key=None)
    check("无 token 取 key 401 + no-store", st == 401 and "no-store" in (h.get("Cache-Control") or ""), f"{st}")
    forged = etok["token"][:-2] + ("AA" if not etok["token"].endswith("AA") else "BB")
    check("伪造 token 取 key 401", http("GET", key_uri, key=None, headers={"Authorization": "Bearer " + forged})[0] == 401)
    check("别的资产的 token 取 key 401",
          http("GET", key_uri, key=None, headers={"Authorization": "Bearer " + tok["token"]})[0] == 401)
    expired = js(token_for(enc_id, ttl=1))
    time.sleep(2.5)
    check("过期 token 取 key 401", http("GET", key_uri, key=None,
                                         headers={"Authorization": "Bearer " + expired["token"]})[0] == 401)
    st, h, key = http("GET", key_uri, key=None, headers={"Authorization": "Bearer " + etok["token"]})
    check("合法 token 取到 16 字节 key + no-store", st == 200 and len(key) == 16 and "no-store" in (h.get("Cache-Control") or ""),
          f"{st} {len(key)} {h.get('Cache-Control')}")
    st, _, key_q = http("GET", key_uri + "?token=" + urllib.request.quote(etok["token"], safe=""), key=None)
    check("?token= 取 key 同样 200", st == 200 and key_q == key)
    dec = subprocess.run(["ffmpeg", "-v", "error", "-headers", f"Authorization: Bearer {etok['token']}\r\n",
                          "-i", BASE + etok["master_url"], "-map", "0:v:0", "-frames:v", "1", "-f", "null", "-"],
                         capture_output=True, text=True)
    check("ffmpeg 带 token 解出一帧", dec.returncode == 0 and not dec.stderr.strip(), dec.stderr.strip()[:200])
    plain_dec = subprocess.run(["ffmpeg", "-v", "error", "-i", BASE + etok["master_url"], "-map", "0:v:0", "-frames:v", "1",
                                "-f", "null", "-"], capture_output=True, text=True)
    check("不带 token 解不开", plain_dec.returncode != 0 or "error" in plain_dec.stderr.lower(),
          plain_dec.stderr.strip()[:120])

    print("== 4. 跨租户与对象存储直读", flush=True)
    other_key = "playback-live-" + uuid.uuid4().hex
    conn = db()
    with conn.cursor() as c:
        c.execute("INSERT INTO vod_tenant(name, status) VALUES ('playback-live-other', 'ACTIVE')")
        tenant = c.lastrowid
        c.execute("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes, status) VALUES (%s,%s,'asset','ACTIVE')",
                  (tenant, hashlib.sha256(other_key.encode()).hexdigest()))
    conn.close()
    check("跨租户签 token 404", token_for(plain_id, key=other_key)[0] == 404)
    probe = ("import urllib.request, urllib.error\n"
             f"url = 'http://minio:9000/vod-hls/{plain.get('hls_prefix')}master.m3u8'\n"
             "try:\n"
             "    print(urllib.request.urlopen(url, timeout=10).status)\n"
             "except urllib.error.HTTPError as e:\n"
             "    print(e.code)\n")
    rc, out, err = compose_exec("api", probe)
    check("容器内匿名直读 MinIO HLS 对象被拒", rc == 0 and out in ("403", "401"), f"rc={rc} out={out} err={err}")
    st, _, _ = http("GET", f"{UPLOAD}/vod-hls/{plain.get('hls_prefix')}master.m3u8", key=None)
    check("上传网关不放行 GET", st in (403, 405), st)

    print("== 5. QoE", flush=True)
    body = {"token": tok["token"], "session_id": "live-" + uuid.uuid4().hex[:12],
            "events": [{"type": "startup", "ms": 900}, {"type": "rebuffer", "ms": 1200},
                       {"type": "error", "code": "fragLoadError"}, {"type": "ended"}]}
    st, h, _ = http("POST", "/api/v1/qoe", key=None, data=json.dumps(body).encode(), headers={"Content-Type": "text/plain"})
    check("QoE 上报 204 + no-store（不需要 API Key）", st == 204 and "no-store" in (h.get("Cache-Control") or ""), f"{st}")
    check("QoE 无效 token 401", http("POST", "/api/v1/qoe", key=None,
                                     data=json.dumps({**body, "token": "x"}).encode())[0] == 401)
    rc, out, err = compose_exec("api", "import urllib.request\n"
                                       "t = urllib.request.urlopen('http://vod-api:8001/actuator/prometheus', timeout=10).read().decode()\n"
                                       "print('\\n'.join(l for l in t.splitlines() if l.startswith('vod_qoe_')))\n")
    check("vod-api /actuator/prometheus 有 QoE 指标",
          rc == 0 and all(n in out for n in ("vod_qoe_startup_seconds_count", "vod_qoe_rebuffer_total",
                                             "vod_qoe_rebuffer_seconds_sum", 'vod_qoe_playback_errors_total{code="fragLoadError"')),
          err or out[:200])

    ok = sum(1 for _, x in results if x)
    print(f"\n== 结果：{ok}/{len(results)} 通过")
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
