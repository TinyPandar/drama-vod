#!/usr/bin/env python3
"""不需要 Docker：用本机 nginx（需带 secure_link 模块）跑 nginx/templates/default.conf.template，核对 Phase 4 的边缘行为。

做法：source nginx/entrypoint.d/05-vod-env.envsh 得到默认值和 CORS map，像 nginx 镜像一样只替换已定义的环境变量，
再把 upstream 主机名换成本机假服务（假回源 = FastAPI /internal/origin，假 vod-api = /keys/v1、/api/v1/qoe），端口换成空闲端口。
签名用 docs/contracts/hls-sign-vectors.json 同一算法现算。只核对 Nginx 配置本身，不代表 compose 栈验收。

  python3 scripts/nginx_local_check.py [--nginx /opt/homebrew/bin/nginx]
"""
import argparse, base64, hashlib, http.server, json, os, re, shutil, socket, subprocess, sys, tempfile, threading, time
import urllib.error, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SECRET = "local-check-hls-secret-0123456789abcdef"
ORIGIN = "local-check-origin-secret-0123456789ab"
ALLOWED = "https://player.example.com"
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail and not ok else ""), flush=True)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def sig(raw):
    return base64.urlsafe_b64encode(hashlib.md5(raw.encode()).digest()).decode().rstrip("=")


def gen_url(exp, asset, gen, file="master.m3u8"):
    return f"/hls/v1/{sig(f'{exp}/v1/{asset}/{gen} {SECRET}')}/{exp}/{asset}/{gen}/{file}"


def v1_url(exp, asset, file="master.m3u8"):
    return f"/hls/v1/{sig(f'{exp}/v1/{asset} {SECRET}')}/{exp}/{asset}/{file}"


def legacy_url(exp, asset, file="master.m3u8"):
    return f"/hls/{sig(f'{exp}/{asset} {SECRET}')}/{exp}/{asset}/{file}"


class Origin(http.server.BaseHTTPRequestHandler):
    hits = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        Origin.hits.append(self.path)
        if self.path.startswith("/internal/origin/"):
            if self.headers.get("X-Origin-Secret") != ORIGIN:
                return self.reply(403, b"origin denied")
            if "missing" in self.path:
                return self.reply(404, b"nope")
            body = (b"#EXTM3U\n" if self.path.endswith(".m3u8") else bytes(range(256)) * 8)
            extra = {"Content-Type": "image/jpeg"} if self.path.endswith(".jpg") else {}
            return self.reply(200, body, {"Cache-Control": "private, max-age=1", **extra})  # Nginx 必须隐藏/覆盖
        if self.path.startswith("/keys/v1/"):
            return self.reply(200, b"0123456789abcdef", {"Cache-Control": "no-store, private"})
        if self.path.startswith("/keys/"):
            return self.reply(401, b"legacy keys", {"Cache-Control": "no-store"})
        self.reply(404, b"")

    def do_PUT(self):
        # 模拟 MinIO：预签名 PUT（含 multipart 分片）回 ETag，并且自己也带一份 CORS 头（网关必须隐藏掉）
        Origin.hits.append(self.path)
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        self.reply(200, b"", {"ETag": '"0123abcd"', "Access-Control-Allow-Origin": self.headers.get("Origin") or "*",
                              "Access-Control-Expose-Headers": "Date, Server"})

    def do_POST(self):
        Origin.hits.append(self.path)
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        self.reply(204, b"", {"Cache-Control": "no-store"})

    def reply(self, code, body, headers=None):
        self.send_response(code)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)


def render(tmp, ports, env_overrides):
    conf_dir = os.path.join(tmp, "conf.d")
    os.makedirs(conf_dir)
    env = {"PATH": os.environ.get("PATH", ""), "HLS_SECRET": SECRET, "ORIGIN_SECRET": ORIGIN, "VOD_ENV": "dev",
           "VOD_CORS_ORIGINS": ALLOWED, "VOD_NGINX_CONF_DIR": conf_dir, **env_overrides}
    out = subprocess.run(["sh", "-c", ". ./nginx/entrypoint.d/05-vod-env.envsh && env"], cwd=ROOT, env=env,
                         capture_output=True, text=True, check=True).stdout
    defined = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
    text = open(os.path.join(ROOT, "nginx", "templates", "default.conf.template"), encoding="utf-8").read()
    for name in sorted(defined, key=len, reverse=True):  # 与 envsubst 一样：只换已定义变量
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            text = text.replace("${" + name + "}", defined[name])
            text = re.sub(r"\$" + name + r"(?![A-Za-z0-9_])", lambda m: defined[name], text)
    left = sorted(set(re.findall(r"\$\{([A-Z_][A-Z0-9_]*)\}", text)))
    if left:
        raise SystemExit(f"template variables without values: {left}")
    text = (text.replace("listen 8080;", f"listen 127.0.0.1:{ports['http']};")
            .replace("listen 9000;", f"listen 127.0.0.1:{ports['upload']};")
            .replace("server api:8000;", f"server 127.0.0.1:{ports['origin']};")
            .replace("server minio:9000;", f"server 127.0.0.1:{ports['origin']};")
            .replace("vod-api:8001", f"127.0.0.1:{ports['vodapi']}")
            .replace("/var/cache/nginx/hls", os.path.join(tmp, "cache"))
            .replace("/var/log/nginx/access.log", os.path.join(tmp, "access.log"))
            .replace("/usr/share/nginx/html", os.path.join(ROOT, "nginx", "html")))
    open(os.path.join(conf_dir, "default.conf"), "w", encoding="utf-8").write(text)
    main_conf = os.path.join(tmp, "nginx.conf")
    open(main_conf, "w").write(f"""
worker_processes 1;
pid {tmp}/nginx.pid;
error_log {tmp}/error.log warn;
events {{ worker_connections 64; }}
http {{
    client_body_temp_path {tmp}/body;
    proxy_temp_path {tmp}/proxy;
    fastcgi_temp_path {tmp}/fastcgi;
    uwsgi_temp_path {tmp}/uwsgi;
    scgi_temp_path {tmp}/scgi;
    include {conf_dir}/*.conf;
}}
""")
    return main_conf


def req(port, path, method="GET", headers=None, data=None):
    r = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method, headers=headers or {}, data=data)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--nginx", default=shutil.which("nginx") or "nginx")
    args = ap.parse_args()
    if not shutil.which(args.nginx) and not os.path.exists(args.nginx):
        raise SystemExit("nginx not found; this check needs a local nginx with secure_link")
    ports = {k: free_port() for k in ("http", "upload", "origin", "vodapi")}
    servers = []
    for key in ("origin", "vodapi"):
        srv = http.server.ThreadingHTTPServer(("127.0.0.1", ports[key]), Origin)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        servers.append(srv)
    tmp = tempfile.mkdtemp(prefix="vod-nginx-")
    conf = render(tmp, ports, {"VOD_KEY_RATE": "2r/s", "VOD_KEY_BURST": "2", "VOD_QOE_MAX_BODY": "1k"})
    t = subprocess.run([args.nginx, "-t", "-c", conf, "-p", tmp], capture_output=True, text=True)
    check("rendered template passes nginx -t", t.returncode == 0, t.stderr[-400:])
    if t.returncode != 0:
        return finish()
    proc = subprocess.Popen([args.nginx, "-c", conf, "-p", tmp, "-g", "daemon off;"], stderr=subprocess.PIPE)
    time.sleep(0.8)
    try:
        run_checks(ports["http"])
        run_upload_checks(ports["upload"])
    finally:
        proc.terminate()
        proc.wait(5)
        for srv in servers:
            srv.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)
    return finish()


def run_upload_checks(port):
    """Phase 6：上传网关。multipart 分片的预签名 PUT 原样转给存储；浏览器能读 ETag；CORS 头只有一份。"""
    Origin.hits.clear()
    path = "/vod-source/raw/t1/9/source.mp4?uploadId=abc&partNumber=3&X-Amz-Signature=x"
    st, h, _ = req(port, path, method="PUT", headers={"Origin": ALLOWED}, data=b"x" * 1024)
    check("upload gateway forwards part PUT with query", st == 200 and Origin.hits == [path], f"{st} {Origin.hits}")
    check("upload gateway keeps ETag", h.get("ETag") == '"0123abcd"', h.get("ETag"))
    acao = h.get_all("Access-Control-Allow-Origin") or []
    check("upload gateway single Allow-Origin", acao == [ALLOWED], acao)
    expose = h.get_all("Access-Control-Expose-Headers") or []
    check("upload gateway exposes ETag", expose == ["ETag"], expose)
    st, h, _ = req(port, "/vod-source/raw/t1/9/source.mp4", method="OPTIONS",
                   headers={"Origin": ALLOWED, "Access-Control-Request-Method": "PUT"})
    check("upload gateway preflight 204", st == 204 and "PUT" in (h.get("Access-Control-Allow-Methods") or ""), st)
    st, _, _ = req(port, "/vod-source/raw/t1/9/source.mp4?uploadId=abc")
    check("upload gateway still rejects GET", st == 403, st)


def run_checks(port):
    now = int(time.time())
    exp = now + 600

    # ---- 带 gen 的 Core 路径 ----
    Origin.hits.clear()
    st, h, _ = req(port, gen_url(exp, 12, "34-2"), headers={"Origin": ALLOWED})
    check("gen master 200 MISS", st == 200 and h.get("X-Cache-Status") == "MISS", f"{st} {h.get('X-Cache-Status')}")
    check("gen origin path carries gen", Origin.hits == ["/internal/origin/v1/12/34-2/master.m3u8"], Origin.hits)
    cc = h.get_all("Cache-Control") or []
    check("gen Cache-Control public immutable (single header)",
          len(cc) == 1 and cc[0] == "public, max-age=86400, immutable", cc)
    check("gen CORS echoes allowed origin", h.get("Access-Control-Allow-Origin") == ALLOWED, h.get("Access-Control-Allow-Origin"))
    st, h, _ = req(port, gen_url(exp + 1, 12, "34-2"))  # 另一个签名/过期时间
    check("gen master HIT across signatures", st == 200 and h.get("X-Cache-Status") == "HIT", h.get("X-Cache-Status"))
    st, h, _ = req(port, gen_url(exp, 12, "34-3"))
    check("cache key includes gen (other gen MISS)", st == 200 and h.get("X-Cache-Status") == "MISS", h.get("X-Cache-Status"))
    st, h, _ = req(port, gen_url(exp, 12, "34-2"), headers={"Origin": "https://evil.example.com"})
    check("gen CORS omitted for other origins", h.get("Access-Control-Allow-Origin") is None, h.get("Access-Control-Allow-Origin"))

    seg = gen_url(exp, 12, "34-2", "720p/seg_00000.ts")
    st1, h1, _ = req(port, seg, headers={"Range": "bytes=0-99"})
    st2, h2, body = req(port, seg, headers={"Range": "bytes=0-99"})
    check("Range on cache HIT is 206", st2 == 206 and h2.get("X-Cache-Status") == "HIT" and len(body) == 100
          and (h2.get("Content-Range") or "").startswith("bytes 0-99/"),
          f"miss={st1}/{h1.get('X-Cache-Status')} hit={st2}/{h2.get('X-Cache-Status')} {h2.get('Content-Range')}")
    st3, h3, body3 = req(port, seg, headers={"Range": "bytes=100-199,300-399"})
    check("multi-range on HIT also served by nginx", st3 == 206, st3)
    check("206 keeps immutable Cache-Control", h2.get("Cache-Control") == "public, max-age=86400, immutable",
          h2.get("Cache-Control"))

    # ---- Phase 6：封面在同一 gen 目录，签名与 master 相同 ----
    Origin.hits.clear()
    st, h, _ = req(port, gen_url(exp, 12, "34-2", "poster.jpg"))
    check("gen poster.jpg 200 image/jpeg via origin", st == 200 and h.get("Content-Type") == "image/jpeg"
          and Origin.hits == ["/internal/origin/v1/12/34-2/poster.jpg"], f"{st} {h.get('Content-Type')} {Origin.hits}")
    check("gen poster.jpg immutable", h.get("Cache-Control") == "public, max-age=86400, immutable", h.get("Cache-Control"))
    st, _, _ = req(port, gen_url(exp, 12, "34-2", "poster.jpg").replace("/34-2/", "/34-3/"))
    check("poster signed for one gen is 403 on another gen", st == 403, st)

    st, h, _ = req(port, gen_url(exp, 12, "34-2").replace("/hls/v1/", "/hls/v1/A", 1))
    check("forged gen signature 403 no-store", st == 403 and h.get("Cache-Control") == "no-store", f"{st} {h.get('Cache-Control')}")
    st, h, _ = req(port, gen_url(now - 10, 12, "34-2"))
    check("expired gen URL 410 no-store", st == 410 and h.get("Cache-Control") == "no-store", f"{st} {h.get('Cache-Control')}")
    asset_sig = sig(f"{exp}/v1/12 {SECRET}")
    st, _, _ = req(port, f"/hls/v1/{asset_sig}/{exp}/12/34-2/master.m3u8")
    check("asset-level signature cannot read a gen directory", st == 403, st)
    forged_gen = gen_url(exp, 12, "34-2").replace("/34-2/", "/34-9/")
    check("signature bound to gen (swap gen -> 403)", req(port, forged_gen)[0] == 403)
    st, h, _ = req(port, gen_url(exp, 12, "34-2", "missing.ts"))
    check("origin 404 on gen path is no-store", st == 404 and h.get("Cache-Control") == "no-store", f"{st} {h.get('Cache-Control')}")
    st, h, _ = req(port, gen_url(exp, 12, "34-2"), method="OPTIONS",
                   headers={"Origin": ALLOWED, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "range"})
    check("gen preflight 204 with CORS", st == 204 and h.get("Access-Control-Allow-Origin") == ALLOWED
          and "GET" in (h.get("Access-Control-Allow-Methods") or ""), f"{st} {dict(h)}")

    # ---- Phase 4 之前的固定前缀 Core 路径：URL 形状不变 ----
    st, h, _ = req(port, v1_url(exp, 7))
    check("fixed-prefix v1 master 200, short TTL", st == 200 and h.get("Cache-Control") == "public, max-age=10",
          f"{st} {h.get('Cache-Control')}")
    st, h, _ = req(port, v1_url(exp, 7, "720p/seg_00000.ts"))
    check("fixed-prefix v1 segment long TTL", st == 200 and h.get("Cache-Control") == "public, max-age=86400",
          h.get("Cache-Control"))
    st, h, _ = req(port, v1_url(exp, 7).replace("/hls/v1/", "/hls/v1/A", 1))
    check("fixed-prefix v1 forged 403 no-store", st == 403 and h.get("Cache-Control") == "no-store", f"{st} {h.get('Cache-Control')}")
    st, h, _ = req(port, v1_url(now - 10, 7))
    check("fixed-prefix v1 expired 410 no-store", st == 410 and h.get("Cache-Control") == "no-store", f"{st} {h.get('Cache-Control')}")

    # ---- 旧 Demo 路由冻结：不加 Phase 4 的头 ----
    Origin.hits.clear()
    st, h, _ = req(port, legacy_url(exp, 7), headers={"Origin": ALLOWED})
    # 旧路由不隐藏也不追加 Cache-Control：回源给什么就是什么（真实 FastAPI 回源不带这个头）
    check("legacy /hls/ unchanged (no Cache-Control/CORS added)", st == 200 and h.get_all("Cache-Control") == ["private, max-age=1"]
          and h.get("Access-Control-Allow-Origin") is None and Origin.hits == ["/internal/origin/legacy/7/master.m3u8"],
          f"{st} {h.get('Cache-Control')} {Origin.hits}")
    st, h, _ = req(port, legacy_url(exp, 7).replace("/hls/", "/hls/A", 1))
    check("legacy forged still 403", st == 403)
    st, h, _ = req(port, legacy_url(now - 10, 7))
    check("legacy expired still 410", st == 410)
    Origin.hits.clear()
    req(port, "/keys/7")
    check("legacy /keys/{id} still goes to FastAPI", Origin.hits == ["/keys/7"], Origin.hits)

    # ---- /keys/v1/ ----
    Origin.hits.clear()
    time.sleep(1.1)
    st, h, body = req(port, "/keys/v1/5", headers={"Authorization": "Bearer x", "Origin": ALLOWED})
    cc = h.get_all("Cache-Control") or []
    check("keys v1 proxied to vod-api, no-store private once", st == 200 and body == b"0123456789abcdef"
          and cc == ["no-store, private"] and Origin.hits == ["/keys/v1/5"], f"{st} {cc} {Origin.hits}")
    check("keys v1 CORS", h.get("Access-Control-Allow-Origin") == ALLOWED)
    st, h, _ = req(port, "/keys/v1/5", method="OPTIONS",
                   headers={"Origin": ALLOWED, "Access-Control-Request-Method": "GET",
                            "Access-Control-Request-Headers": "authorization"})
    check("keys v1 preflight allows Authorization", st == 204 and "Authorization" in (h.get("Access-Control-Allow-Headers") or ""),
          f"{st} {h.get('Access-Control-Allow-Headers')}")
    st, _, _ = req(port, "/keys/v1/5", method="POST", data=b"")
    check("keys v1 only GET", st == 405, st)
    time.sleep(1.1)
    codes = [req(port, "/keys/v1/5")[0] for _ in range(12)]
    check("keys v1 limit_req returns 429 when flooded", 429 in codes and codes[0] == 200, codes)
    st, h, _ = [req(port, "/keys/v1/5") for _ in range(3)][-1]
    if st == 429:
        check("429 from limit_req is no-store", (h.get("Cache-Control") or "").startswith("no-store"), h.get("Cache-Control"))

    # ---- QoE ----
    st, h, _ = req(port, "/api/v1/qoe", method="POST", data=b'{"x":1}', headers={"Content-Type": "text/plain"})
    check("qoe proxied 204 no-store", st == 204 and h.get_all("Cache-Control") == ["no-store"], f"{st} {h.get_all('Cache-Control')}")
    st, h, _ = req(port, "/api/v1/qoe", method="POST", data=b"x" * 4096, headers={"Content-Type": "text/plain"})
    check("qoe body over nginx limit 413", st == 413, st)


def finish():
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed" + (f"; failed: {failed}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
