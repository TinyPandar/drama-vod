#!/usr/bin/env python3
"""实机验证核心 /api/v1：预签名上传、转码、签名播放、租户隔离、过期租约恢复。"""
import hashlib, json, os, subprocess, time, urllib.error, urllib.request
import pymysql

BASE = "http://nginx:8080"
KEY = "dev-demo-key"
OTHER = "other-tenant-key"


def http(method, path, body=None, data=None, key=KEY):
    url = path if path.startswith("http") else BASE + path
    headers = {}
    if key:
        headers["X-Api-Key"] = key
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            payload = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            payload = {"raw": raw[:200].decode("utf-8", "replace")}
        return e.code, payload


def db():
    return pymysql.connect(host=os.environ["MYSQL_HOST"], user=os.environ["MYSQL_USER"],
                           password=os.environ["MYSQL_PASSWORD"], database=os.environ["MYSQL_DB"],
                           cursorclass=pymysql.cursors.DictCursor, autocommit=True)


def mp4(path, seconds):
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", str(seconds),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", path], check=True)


def upload(seconds, title):
    mp4(f"/tmp/{title}.mp4", seconds)
    status, opened = http("POST", "/api/v1/uploads", {"encrypted": False}, key=KEY)
    assert status == 200, (status, opened)
    put = urllib.request.Request(opened["put_url"], data=open(f"/tmp/{title}.mp4", "rb").read(), method="PUT")
    with urllib.request.urlopen(put, timeout=60) as resp:
        assert resp.status in (200, 204), resp.status
    return opened


def wait_ready(asset_id, timeout=150):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        status, asset = http("GET", f"/api/v1/assets/{asset_id}")
        last = (status, asset)
        if status == 200 and asset.get("status") == "READY":
            return asset
        if status == 200 and asset.get("status") == "FAILED":
            raise SystemExit(f"asset {asset_id} FAILED")
        time.sleep(3)
    raise SystemExit(f"asset {asset_id} not ready: {last}")


def main():
    opened = upload(8, "core")
    status, done = http("POST", f"/api/v1/uploads/{opened['session_id']}/complete")
    assert status == 200, (status, done)
    again_status, again = http("POST", f"/api/v1/uploads/{opened['session_id']}/complete")
    assert again_status == 200 and again["job_id"] == done["job_id"], (again_status, again)
    print("PASS core complete 幂等", done, flush=True)
    asset = wait_ready(opened["asset_id"])
    print("PASS core 转码 READY", asset, flush=True)
    status, play = http("GET", f"/api/v1/assets/{opened['asset_id']}/playback")
    assert status == 200 and play["master_url"].startswith("/hls/v1/"), (status, play)
    decode = subprocess.run(["ffmpeg", "-v", "error", "-i", BASE + play["master_url"], "-f", "null", "-"],
                            capture_output=True, text=True)
    assert decode.returncode == 0, decode.stderr[-400:]
    print("PASS core 签名播放可解码", play["master_url"], flush=True)

    other_hash = hashlib.sha256(OTHER.encode()).hexdigest()
    conn = db()
    with conn.cursor() as c:
        c.execute("INSERT INTO vod_tenant(name, status) VALUES ('other', 'ACTIVE')")
        tenant_id = c.lastrowid
        c.execute("INSERT INTO vod_api_credential(tenant_id, key_hash, scopes, status) VALUES (%s,%s,'*','ACTIVE')",
                  (tenant_id, other_hash))
    conn.close()
    hidden, _ = http("GET", f"/api/v1/assets/{opened['asset_id']}", key=OTHER)
    assert hidden == 404, hidden
    print("PASS 其他租户看不到该资产", hidden, flush=True)

    stuck = upload(8, "lease")
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT tenant_id FROM vod_media_asset WHERE id=%s", (stuck["asset_id"],))
        tenant_id = c.fetchone()["tenant_id"]
        c.execute("UPDATE vod_media_asset SET status='UPLOADED' WHERE id=%s", (stuck["asset_id"],))
        c.execute(
            "INSERT INTO vod_transcode_job(tenant_id, asset_id, profile, status, attempts, locked_until, worker_id) "
            "VALUES (%s,%s,'ladder_v1','RUNNING',1, NOW() - INTERVAL 2 MINUTE, 'dead-worker')",
            (tenant_id, stuck["asset_id"]))
        job_id = c.lastrowid
    conn.close()
    print("LEASE_INSERTED", job_id, stuck["asset_id"], flush=True)
    recovered = wait_ready(stuck["asset_id"], timeout=150)
    print("PASS 过期租约被扫回并转码完成", recovered, flush=True)


if __name__ == "__main__":
    main()
