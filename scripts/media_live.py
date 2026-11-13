#!/usr/bin/env python3
"""Phase 6 高级媒体能力验收：Multipart 断点续传 + 转码封面。在仓库根目录运行。

compose 栈（需要 Docker、本机 ffmpeg；PyMySQL 可选，用于把会话改成已过期来验证清理）：

  docker compose -p drama-vod-p6 -f docker-compose.yml -f docker-compose.media-live.yml up -d --build
  COMPOSE_PROJECT_NAME=drama-vod-p6 python3 scripts/media_live.py

覆盖：GET /uploads/config；mode=multipart 开会话、Idempotency-Key 重放同一会话；先传一部分分片 → GET /parts 列出已传 →
只补缺的分片 → 错 etag 的 complete 400 且不建任务 → 正确 complete 建任务 → 等 READY → 资产详情 / 列表 / playback-token
都有 poster_url，经边缘取到 image/jpeg，篡改签名 403，封面对象落在当期 {job}-{claim_version} 前缀；
abort 后存储侧没有未完成分片；会话过期后后台清理 abort、存储侧没有残留；mode=put 默认路径仍可用。

查看存储侧 multipart 残留：默认 `docker compose exec -T api python`（api 容器里有 boto3 和 S3 凭据）；
设 MEDIA_LIVE_S3_ENDPOINT（加 S3_ACCESS_KEY / S3_SECRET_KEY）时直接用本机 boto3 连。
不经边缘（没有 Nginx）时设 MEDIA_LIVE_SKIP_EDGE=1，改为直接在存储里核对封面对象。
只报告真实跑出的结果，不做性能结论。
"""
import base64, json, os, pathlib, subprocess, sys, tempfile, time, urllib.error, urllib.request, uuid

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
KEY = os.environ.get("MEDIA_LIVE_API_KEY") or dotenv("VOD_DEV_API_KEY") or "dev-demo-key"
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "drama-vod-p6")
TIMEOUT = int(os.environ.get("MEDIA_LIVE_TIMEOUT", "300"))
CLEANUP_TIMEOUT = int(os.environ.get("MEDIA_LIVE_CLEANUP_TIMEOUT", "180"))
SKIP_EDGE = os.environ.get("MEDIA_LIVE_SKIP_EDGE") == "1"
SOURCE_BUCKET = os.environ.get("SOURCE_BUCKET", "vod-source")
HLS_BUCKET = os.environ.get("HLS_BUCKET", "vod-hls")
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail and not ok else ""), flush=True)
    return ok


def skip(name, why):
    print(f"SKIP {name}  ({why})", flush=True)


def http(method, path, body=None, headers=None, data=None, key=KEY, timeout=120):
    url = path if path.startswith("http") else BASE + path
    headers = dict(headers or {})
    if key:
        headers["X-Api-Key"] = key
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def api(method, path, body=None, headers=None):
    st, h, raw = http(method, path, body, headers)
    try:
        return st, json.loads(raw) if raw else {}
    except ValueError:
        return st, {"raw": raw[:200]}


# ---------- 存储侧检查 ----------
def s3_script(code):
    """在能连到 MinIO 的地方跑一小段 boto3（默认 api 容器）。code 里用变量 s3，最后 print(json)。"""
    prelude = ("import json, os, boto3\nfrom botocore.config import Config\n"
               "s3 = boto3.client('s3', endpoint_url=os.environ.get('MEDIA_LIVE_S3_ENDPOINT') or os.environ['S3_ENDPOINT'],"
               " aws_access_key_id=os.environ['S3_ACCESS_KEY'], aws_secret_access_key=os.environ['S3_SECRET_KEY'],"
               " region_name='us-east-1', config=Config(signature_version='s3v4', s3={'addressing_style': 'path'}))\n")
    if os.environ.get("MEDIA_LIVE_S3_ENDPOINT"):
        out = subprocess.run([sys.executable, "-I", "-c", prelude + code], capture_output=True, text=True, timeout=60)
    else:
        out = subprocess.run(["docker", "compose", "-p", PROJECT, "exec", "-T", "api", "python", "-c", prelude + code],
                             cwd=ROOT, capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[-400:])
    return json.loads(out.stdout.strip().splitlines()[-1])


def pending_uploads(key):
    return s3_script(f"r = s3.list_multipart_uploads(Bucket={SOURCE_BUCKET!r}, Prefix={key!r})\n"
                     "print(json.dumps([u['UploadId'] for u in r.get('Uploads', [])]))")


def head(bucket, key):
    return s3_script(f"try:\n    r = s3.head_object(Bucket={bucket!r}, Key={key!r})\n"
                     "    print(json.dumps({'size': r['ContentLength'], 'type': r.get('ContentType')}))\n"
                     "except Exception as e:\n    print(json.dumps(None))")


def db():
    try:
        import pymysql
    except ImportError:
        return None
    try:
        return pymysql.connect(host=os.environ.get("MYSQL_HOST", "127.0.0.1"), port=int(dotenv("MYSQL_PORT") or 3306),
                               user=os.environ.get("MYSQL_USER", "vod"), password=dotenv("MYSQL_PASSWORD"),
                               database=os.environ.get("MYSQL_DB", "vod"), autocommit=True)
    except Exception as e:
        print(f"   MySQL not reachable ({e}); expiry cleanup check will be skipped", flush=True)
        return None


# ---------- 上传 ----------
def put_part(url, data):
    st, h, _ = http("PUT", url, data=data, key=None, headers={"Content-Type": "application/octet-stream"})
    etag = next((v for k, v in h.items() if k.lower() == "etag"), None)
    return st, etag


def sign(session_id, numbers):
    st, out = api("POST", f"/api/v1/uploads/{session_id}/parts", {"part_numbers": numbers})
    if st != 200:
        raise RuntimeError(f"sign parts {numbers}: {st} {out}")
    return {p["part_number"]: p["url"] for p in out["parts"]}


def chunks(path, size):
    data = pathlib.Path(path).read_bytes()
    return [data[i:i + size] for i in range(0, len(data), size)]


def make_video(path, min_bytes):
    """生成一段比 min_bytes 大的真实视频（噪点画面压不小），保证至少两个分片。"""
    for secs in (6, 12, 24):
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size=720x1280:rate=30:duration={secs}",
                        "-f", "lavfi", "-i", f"sine=frequency=440:duration={secs}",
                        "-vf", "noise=alls=60:allf=t+u", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "24M",
                        "-maxrate", "24M", "-bufsize", "48M", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", path],
                       check=True)
        if os.path.getsize(path) > min_bytes:
            return
    raise SystemExit(f"could not generate a video larger than {min_bytes} bytes")


def wait_for(fn, timeout, step=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return None


def main():
    st, cfg = api("GET", "/api/v1/uploads/config")
    if st != 200:
        raise SystemExit(f"vod-api not reachable at {BASE} or key rejected ({st} {cfg})")
    part = int(cfg["part_size_bytes"])
    check("upload config exposes part size and threshold",
          part >= 5 * 1024 * 1024 and int(cfg["multipart_threshold_bytes"]) > 0, cfg)
    tmp = tempfile.mkdtemp(prefix="media-live-")
    src = os.path.join(tmp, "big.mp4")
    make_video(src, part + 1024 * 1024)
    pieces = chunks(src, part)
    print(f"   source {os.path.getsize(src)} bytes, {len(pieces)} parts of {part}", flush=True)

    # ---- multipart：开会话、重放 ----
    idem = "media-live-" + uuid.uuid4().hex
    st, opened = api("POST", "/api/v1/uploads", {"mode": "multipart", "filename": "big.mp4"}, {"Idempotency-Key": idem})
    check("open multipart 200 without put_url", st == 200 and opened.get("mode") == "multipart"
          and "put_url" not in opened and opened.get("part_size_bytes") == part, f"{st} {opened}")
    sid, asset = opened["session_id"], opened["asset_id"]
    _, again = api("POST", "/api/v1/uploads", {"mode": "multipart", "filename": "big.mp4"}, {"Idempotency-Key": idem})
    check("idempotent replay returns same multipart session", again.get("session_id") == sid and "put_url" not in again, again)
    source_key = None

    # ---- 先传第 1 块，「断线」，列出已传分片后只补缺的 ----
    urls = sign(sid, [1])
    st1, e1 = put_part(urls[1], pieces[0])
    check("part 1 PUT via presigned URL returns ETag", st1 == 200 and e1, f"{st1} {e1}")
    st, listed = api("GET", f"/api/v1/uploads/{sid}/parts")
    have = {p["part_number"]: p["etag"] for p in listed.get("parts", [])}
    check("GET parts lists the uploaded part", st == 200 and list(have) == [1], f"{st} {listed}")
    missing = [n for n in range(1, len(pieces) + 1) if n not in have]
    urls = sign(sid, missing)
    etags = dict(have)
    for n in missing:
        stn, en = put_part(urls[n], pieces[n - 1])
        etags[n] = en
        if stn != 200:
            check(f"part {n} PUT", False, stn)
    parts = [{"part_number": n, "etag": etags[n]} for n in sorted(etags)]
    st, _ = api("POST", f"/api/v1/uploads/{sid}/complete", {"parts": [dict(parts[0], etag="0" * 32)] + parts[1:]})
    st_job, job_after_bad = api("GET", f"/api/v1/assets/{asset}")
    check("complete with wrong etag is 4xx and creates no job", 400 <= st < 500 and job_after_bad.get("status") == "UPLOADING",
          f"{st} {job_after_bad.get('status')}")
    st, done = api("POST", f"/api/v1/uploads/{sid}/complete", {"parts": parts})
    check("complete multipart creates job", st == 200 and done.get("job_id"), f"{st} {done}")
    st, aborted = api("POST", f"/api/v1/uploads/{sid}/abort")
    check("abort after complete is 409", st == 409, st)

    # ---- 等 READY，核对封面 ----
    def ready():
        s, a = api("GET", f"/api/v1/assets/{asset}")
        return a if a.get("status") in ("READY", "FAILED") else None
    detail = wait_for(ready, TIMEOUT)
    check("multipart asset becomes READY", detail and detail["status"] == "READY", detail and detail.get("status"))
    if detail and detail.get("source"):
        source_key = detail["source"]["object_key"]
        check("source size equals uploaded file", detail["source"]["size"] == os.path.getsize(src), detail["source"])
    if detail and detail["status"] == "READY":
        prefix = detail["hls_prefix"]
        poster = detail.get("poster_url")
        gen = prefix.rstrip("/").split("/")[-1]
        check("asset detail has poster_url in current gen", poster and f"/{asset}/{gen}/poster.jpg" in poster, poster)
        _, page = api("GET", "/api/v1/assets?limit=50")
        row = next((a for a in page.get("assets", []) if a["id"] == asset), {})
        check("asset list has poster_url", bool(row.get("poster_url")), row)
        st, tok = api("POST", f"/api/v1/assets/{asset}/playback-token", {})
        check("playback-token returns poster_url with same exp as master",
              st == 200 and tok.get("poster_url") and f"/{tok['expires_at']}/" in tok["poster_url"], tok)
        info = head(HLS_BUCKET, prefix + "poster.jpg")
        check("poster object lives in job-scoped prefix", info and info["size"] > 0 and info["type"] == "image/jpeg", info)
        check("no fixed-path poster outside gen", head(HLS_BUCKET, f"assets/v1/{asset}/poster.jpg") is None)
        if SKIP_EDGE:
            skip("poster_url through edge", "MEDIA_LIVE_SKIP_EDGE=1")
        else:
            st, h, body = http("GET", poster, key=None)
            ctype = next((v for k, v in h.items() if k.lower() == "content-type"), "")
            check("poster_url fetch 200 image/jpeg", st == 200 and ctype.startswith("image/jpeg")
                  and body[:2] == b"\xff\xd8", f"{st} {ctype} {body[:4]!r}")
            forged = poster.replace("/hls/v1/", "/hls/v1/A", 1)
            st, _, _ = http("GET", forged, key=None)
            check("forged poster signature 403", st == 403, st)
            st, _, _ = http("GET", tok["master_url"], key=None)
            check("master_url still plays (200)", st == 200, st)
    if source_key:
        check("no pending multipart after complete", pending_uploads(source_key) == [])

    # ---- abort：存储侧不留未完成分片 ----
    st, ab = api("POST", "/api/v1/uploads", {"mode": "multipart"})
    urls = sign(ab["session_id"], [1])
    put_part(urls[1], pieces[0])
    _, ab_asset = api("GET", f"/api/v1/assets/{ab['asset_id']}")
    abort_key = ab_asset["source"]["object_key"]
    check("abort target has a pending storage upload", len(pending_uploads(abort_key)) == 1)
    st, res = api("POST", f"/api/v1/uploads/{ab['session_id']}/abort")
    check("abort 200 ABORTED", st == 200 and res.get("session_status") == "ABORTED", f"{st} {res}")
    check("no pending multipart after abort", pending_uploads(abort_key) == [])
    st, _ = api("GET", f"/api/v1/uploads/{ab['session_id']}/parts")
    check("parts on aborted session 409", st == 409, st)

    # ---- 过期：后台清理 abort ----
    conn = db()
    if conn is None:
        skip("expired multipart cleanup", "PyMySQL / MySQL not available")
    else:
        st, ex = api("POST", "/api/v1/uploads", {"mode": "multipart"})
        urls = sign(ex["session_id"], [1])
        put_part(urls[1], pieces[0])
        _, ex_asset = api("GET", f"/api/v1/assets/{ex['asset_id']}")
        ex_key = ex_asset["source"]["object_key"]
        with conn.cursor() as c:
            c.execute("UPDATE vod_upload_session SET expires_at = NOW() - INTERVAL 5 SECOND WHERE id = %s", (ex["session_id"],))

        def cleaned():
            with conn.cursor() as c:
                c.execute("SELECT status FROM vod_upload_session WHERE id = %s", (ex["session_id"],))
                return c.fetchone()[0] == "ABORTED"
        check("expired multipart session cleaned to ABORTED", wait_for(cleaned, CLEANUP_TIMEOUT))
        check("no pending multipart after expiry cleanup", pending_uploads(ex_key) == [])

    # ---- 默认 put 路径仍可用 ----
    small = os.path.join(tmp, "small.mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x568:rate=25:duration=2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", small], check=True)
    st, op = api("POST", "/api/v1/uploads", {})
    check("default open still returns put_url", st == 200 and op.get("put_url") and "mode" not in op, op)
    st, _, _ = http("PUT", op["put_url"], data=pathlib.Path(small).read_bytes(), key=None,
                    headers={"Content-Type": "video/mp4"})
    st2, done2 = api("POST", f"/api/v1/uploads/{op['session_id']}/complete")
    check("put upload completes", st == 200 and st2 == 200 and done2.get("job_id"), f"{st} {st2} {done2}")

    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} passed" + (f"; failed: {failed}" if failed else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
