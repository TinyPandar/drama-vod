#!/usr/bin/env python3
"""路线 B 资产生命周期验收（需要 docker compose 栈和本机 ffmpeg）。在仓库根目录运行：

  docker compose -f docker-compose.yml -f docker-compose.lifecycle-live.yml up -d --build
  python3 scripts/lifecycle_live.py

叠加文件把上传会话有效期调成 60 秒、各清理间隔调成 2 秒、Worker 的前缀清理宽限期调成 5 秒。
覆盖：上传时带标题和标签、按关键字 / 标签 / 状态筛选；删除 READY 资产后详情、播放 token、重转都 404、列表不再出现，
存储用量退回，源文件和转码产物都从 MinIO 删掉；有进行中的任务时删除 409、取消后可删；
取消上传和上传过期后资产变 DELETED、已传的源对象被删除。
存储侧检查在 api 容器里用 boto3 列对象（与 media_live.py 相同做法）。只报告真实跑出的结果。
"""
import json, os, pathlib, subprocess, sys, tempfile, time, urllib.error, urllib.parse, urllib.request, uuid

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
KEY = os.environ.get("LIFECYCLE_LIVE_API_KEY") or dotenv("VOD_DEV_API_KEY") or "dev-demo-key"
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "drama-vod")
TIMEOUT = int(os.environ.get("LIFECYCLE_LIVE_TIMEOUT", "300"))
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def http(method, path, body=None, data=None, headers=None, key=KEY, timeout=120):
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
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def api(method, path, body=None):
    st, raw = http(method, path, body)
    try:
        return st, json.loads(raw) if raw else {}
    except ValueError:
        return st, {"raw": raw[:200]}


def wait(pred, timeout=TIMEOUT, every=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = pred()
        if value:
            return value
        time.sleep(every)
    return None


def s3_keys(bucket, prefix):
    code = ("import json, os, boto3\nfrom botocore.config import Config\n"
            "s3 = boto3.client('s3', endpoint_url=os.environ['S3_ENDPOINT'], aws_access_key_id=os.environ['S3_ACCESS_KEY'],"
            " aws_secret_access_key=os.environ['S3_SECRET_KEY'], region_name='us-east-1',"
            " config=Config(signature_version='s3v4', s3={'addressing_style': 'path'}))\n"
            f"r = s3.list_objects_v2(Bucket={bucket!r}, Prefix={prefix!r})\n"
            "print(json.dumps([o['Key'] for o in r.get('Contents', [])]))")
    out = subprocess.run(["docker", "compose", "-p", PROJECT, "exec", "-T", "api", "python", "-c", code],
                         cwd=ROOT, capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[-400:])
    return json.loads(out.stdout.strip().splitlines()[-1])


def ffmpeg(path, seconds):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=25",
                    "-f", "lavfi", "-i", "sine=frequency=330", "-t", str(seconds), "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-shortest", str(path)], check=True)


def open_and_put(media, body):
    st, opened = api("POST", "/api/v1/uploads", body)
    assert st == 200, (st, opened)
    st, _ = http("PUT", opened["put_url"], data=media.read_bytes(), key=None, headers={"Content-Type": "video/mp4"})
    assert st == 200, st
    return opened


def asset_status(asset_id):
    st, body = api("GET", f"/api/v1/assets?status=DELETED&q={asset_id}")
    if st == 200 and any(a["id"] == asset_id for a in body.get("assets", [])):
        return "DELETED"
    st, body = api("GET", f"/api/v1/assets/{asset_id}")
    return body.get("status") if st == 200 else f"http {st}"


def used_bytes():
    return api("GET", "/api/v1/usage")[1].get("storage_used_bytes")


def main():
    st, _ = http("GET", "/api/v1/healthz")
    if st != 200:
        sys.exit(f"vod-api not reachable at {BASE} ({st})")
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="lifecycle-live-"))
    media = tmp / "clip.mp4"
    ffmpeg(media, 6)
    suffix = uuid.uuid4().hex[:6]
    tag = f"b-{suffix}"

    print("== 1. 标题、标签与筛选", flush=True)
    opened = open_and_put(media, {"filename": "clip.mp4", "title": f"重逢 第一集 {suffix}", "tags": [tag, "短剧"]})
    asset_id = opened["asset_id"]
    st, done = api("POST", f"/api/v1/uploads/{opened['session_id']}/complete")
    check("complete 建任务", st == 200 and done.get("job_id"), st)
    ready = wait(lambda: api("GET", f"/api/v1/assets/{asset_id}")[1].get("status") == "READY")
    check("转码完成 READY", ready)
    st, asset = api("GET", f"/api/v1/assets/{asset_id}")
    check("详情返回标题、标签和创建时间", asset.get("title") == f"重逢 第一集 {suffix}" and asset.get("tags") == [tag, "短剧"]
          and asset.get("created_at"), asset)
    found = lambda qs: [a["id"] for a in api("GET", "/api/v1/assets?" + qs)[1].get("assets", [])]
    check("按标签筛选", found(urllib.parse.urlencode({"tag": tag})) == [asset_id])
    check("按标题关键字筛选", asset_id in found(urllib.parse.urlencode({"q": f"第一集 {suffix}"})))
    check("按状态筛选", asset_id in found("status=READY&limit=50"))
    st, patched = api("PATCH", f"/api/v1/assets/{asset_id}", {"title": f"重逢（修订）{suffix}", "tags": [tag]})
    check("PATCH 改标题和标签", st == 200 and patched.get("tags") == [tag] and patched.get("title", "").startswith("重逢（修订）"), st)
    prefix = asset.get("hls_prefix")
    source_key = (asset.get("source") or {}).get("object_key")
    hls_before = s3_keys("vod-hls", prefix)
    check("删除前 MinIO 里有源文件和转码产物", s3_keys("vod-source", source_key) == [source_key] and len(hls_before) > 3,
          f"hls objects={len(hls_before)}")
    st, tok = api("POST", f"/api/v1/assets/{asset_id}/playback-token", {})
    master = tok.get("master_url")
    check("删除前能签播放地址", st == 200 and master, st)

    print("== 2. 删除", flush=True)
    before = used_bytes()
    st, deleted = api("DELETE", f"/api/v1/assets/{asset_id}")
    check("DELETE 200 → DELETED（USER）", st == 200 and deleted.get("status") == "DELETED" and deleted.get("deleted_reason") == "USER",
          (st, deleted))
    check("详情 404", api("GET", f"/api/v1/assets/{asset_id}")[0] == 404)
    check("播放 token 404", api("POST", f"/api/v1/assets/{asset_id}/playback-token", {})[0] == 404)
    check("重新转码 404", api("POST", f"/api/v1/assets/{asset_id}/transcodes", {})[0] == 404)
    check("默认列表里不再出现", asset_id not in found(urllib.parse.urlencode({"tag": tag})))
    check("status=DELETED 能查到", asset_id in found(urllib.parse.urlencode({"status": "DELETED", "q": str(asset_id)})))
    check("存储用量退回源文件大小", before - used_bytes() == asset.get("source_size"), f"{before} → {used_bytes()}")
    check("重复删除 200", api("DELETE", f"/api/v1/assets/{asset_id}")[0] == 200)
    gone_source = wait(lambda: s3_keys("vod-source", source_key) == [], 120)
    check("源文件从 MinIO 删除", gone_source)
    gone_hls = wait(lambda: s3_keys("vod-hls", prefix) == [], 180)
    check("转码产物从 MinIO 删除（Worker 前缀清理）", gone_hls, f"{len(s3_keys('vod-hls', prefix))} left")
    # 边缘缓存里可能还有播放列表（缓存期内），这里只看回源：换一个 query 绕不过 secure_link，所以只记录结果
    st, _ = http("GET", master, key=None)
    print(f"INFO 删除并清理后原播放地址返回 {st}（边缘缓存期内可能仍是 200）", flush=True)

    print("== 3. 进行中的任务", flush=True)
    opened = open_and_put(media, {"filename": "clip.mp4", "title": f"busy {suffix}"})
    st, done = api("POST", f"/api/v1/uploads/{opened['session_id']}/complete")
    st, body = api("DELETE", f"/api/v1/assets/{opened['asset_id']}")
    check("有进行中的任务时删除 409", st == 409, (st, body))
    api("POST", f"/api/v1/jobs/{done['job_id']}/cancel")
    st, _ = api("DELETE", f"/api/v1/assets/{opened['asset_id']}")
    if st == 409:  # 任务可能已经跑完再取消返回 409；等它结束再删
        wait(lambda: api("GET", f"/api/v1/jobs/{done['job_id']}")[1].get("status") in ("SUCCEEDED", "FAILED", "CANCELLED"), 120)
        st, _ = api("DELETE", f"/api/v1/assets/{opened['asset_id']}")
    check("任务结束或取消后可以删除", st == 200, st)

    print("== 4. 取消上传与上传过期", flush=True)
    opened = open_and_put(media, {"filename": "clip.mp4", "title": f"abort {suffix}"})
    st, _ = api("POST", f"/api/v1/uploads/{opened['session_id']}/abort")
    check("取消上传 200", st == 200, st)
    check("取消后资产 DELETED", asset_status(opened["asset_id"]) == "DELETED")
    keys = lambda aid: [k for k in s3_keys("vod-source", "raw/") if f"/{aid}/source." in k]
    check("取消后已传的源对象被删除", wait(lambda: keys(opened["asset_id"]) == [], 120))

    opened = open_and_put(media, {"filename": "clip.mp4", "title": f"expire {suffix}"})
    check("过期前源对象在", keys(opened["asset_id"]) != [])
    expired = wait(lambda: asset_status(opened["asset_id"]) == "DELETED", 180, 5)
    check("上传会话过期后资产 DELETED（UPLOAD_EXPIRED）", expired)
    check("过期后源对象被删除", wait(lambda: keys(opened["asset_id"]) == [], 120))
    st, _ = api("POST", f"/api/v1/uploads/{opened['session_id']}/complete")
    check("过期后 complete 409", st == 409, st)

    passed = sum(1 for _, ok in results if ok)
    print(f"\n== 结果：{passed}/{len(results)} 通过", flush=True)
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
