#!/usr/bin/env python3
"""短剧 Demo 调视频云时，不把解锁请求混进去；视频云连不上或超时按 502 返回，不抛异常。"""
import os, socket, sys, threading
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "api"))
import vod_client

seen = []

def transport(method, path, body):
    seen.append((method, path, body))
    if path.endswith("/uploads") and method == "POST":
        return 200, {"session_id": 3, "asset_id": 9, "put_url": "http://localhost:9000/vod-source/raw/t1/9/source.mp4", "expires_in": 900}
    if path.endswith("/complete"):
        return 200, {"asset_id": 9, "job_id": 1, "job_status": "PENDING"}
    if path.endswith("/playback"):
        return 200, {"master_url": "/hls/v1/abc/1/9/master.m3u8", "expires_at": 1}
    if path.endswith("/playback-token"):
        return 200, {"token": "vpt1.x.y", "master_url": "/hls/v1/abc/1/9/3-1/master.m3u8", "expires_at": 1, "encrypted": True}
    return 500, {}

vod_client.open_upload(transport)
vod_client.complete_upload(3, transport)
vod_client.playback(9, transport)
vod_client.playback_token(9, transport=transport)
vod_client.playback_token(9, ttl_seconds=120, viewer_id="v1", transport=transport)
assert [(m, p) for m, p, _ in seen] == [
    ("POST", "/api/v1/uploads"),
    ("POST", "/api/v1/uploads/3/complete"),
    ("GET", "/api/v1/assets/9/playback"),
    ("POST", "/api/v1/assets/9/playback-token"),
    ("POST", "/api/v1/assets/9/playback-token"),
], seen
assert seen[3][2] == {} and seen[4][2] == {"ttl_seconds": 120, "viewer_id": "v1"}, seen[3:]
assert all("unlock" not in path for _, path, _ in seen)
assert "localhost:9000" in transport("POST", "/api/v1/uploads", {})[1]["put_url"]
assert "/api/" not in transport("POST", "/api/v1/uploads", {})[1]["put_url"].split("9000", 1)[1]

# 连接被拒绝（URLError）-> 502
free = socket.socket()
free.bind(("127.0.0.1", 0))
port = free.getsockname()[1]
free.close()
vod_client.BASE = f"http://127.0.0.1:{port}"
status, payload = vod_client.playback_token(9)
assert status == 502 and "unavailable" in payload["detail"], (status, payload)

# 接受连接但不回应（读超时）-> 502
hang = socket.socket()
hang.bind(("127.0.0.1", 0))
hang.listen(1)
held = []
threading.Thread(target=lambda: held.append(hang.accept()), daemon=True).start()
vod_client.BASE = f"http://127.0.0.1:{hang.getsockname()[1]}"
vod_client.TIMEOUT = 0.5
status, payload = vod_client.playback(9)
assert status == 502 and "unavailable" in payload["detail"], (status, payload)
hang.close()
print("vod client ok")
