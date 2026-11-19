#!/usr/bin/env python3
"""短剧 Demo 经视频云上传、转码、播放（vod-upload / vod-complete / vod-play 这组播放页在用的接口）。"""
import json, subprocess, time, urllib.error, urllib.request

BASE = "http://nginx:8080"


def http(method, path, body=None, data=None):
    url = path if path.startswith("http") else BASE + path
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            payload = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            payload = {"raw": raw[:200].decode("utf-8", "replace")}
        return e.code, payload


def main():
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "8",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", "/tmp/demo.mp4"], check=True)
    drama = http("POST", "/api/dramas", {"title": "vod-bridge"})[1]["drama_id"]
    free = http("POST", f"/api/dramas/{drama}/episodes", {"ep_no": 1, "title": "free"})[1]["episode_id"]
    paid = http("POST", f"/api/dramas/{drama}/episodes", {"ep_no": 2, "title": "paid", "is_paid": True})[1]["episode_id"]
    up = http("POST", f"/api/episodes/{free}/vod-upload")
    assert up[0] == 200 and up[1].get("put_url"), up
    put = urllib.request.Request(up[1]["put_url"], data=open("/tmp/demo.mp4", "rb").read(), method="PUT",
                                 headers={"Content-Type": "video/mp4"})
    with urllib.request.urlopen(put, timeout=60) as resp:
        assert resp.status in (200, 204)
    done = http("POST", f"/api/episodes/{free}/vod-complete")
    assert done[0] == 200, done
    print("PASS demo 经视频云建任务", done[1], flush=True)
    asset_id = up[1]["asset_id"]
    deadline = time.time() + 150
    asset = None
    while time.time() < deadline:
        status, asset = http("GET", f"/api/v1/assets/{asset_id}", body=None)
        req = urllib.request.Request(BASE + f"/api/v1/assets/{asset_id}", headers={"X-Api-Key": "dev-demo-key"})
        with urllib.request.urlopen(req, timeout=20) as resp:
            asset = json.loads(resp.read())
        if asset.get("status") == "READY":
            break
        time.sleep(3)
    else:
        raise SystemExit(f"not ready {asset}")
    print("PASS demo 资产 READY", asset_id, flush=True)
    # 路线 C：Core 资产 READY 后由 Demo 的对账线程把这一集切过去（默认每 5 秒一轮）
    deadline = time.time() + 60
    while time.time() < deadline:
        play = http("GET", f"/api/episodes/{free}/vod-play")
        if play[0] == 200:
            break
        time.sleep(2)
    assert play[0] == 200 and play[1]["master_url"].startswith("/hls/v1/"), play
    decode = subprocess.run(["ffmpeg", "-v", "error", "-i", BASE + play[1]["master_url"], "-f", "null", "-"],
                            capture_output=True, text=True)
    assert decode.returncode == 0, decode.stderr[-300:]
    print("PASS demo 经视频云播放", play[1]["master_url"], flush=True)
    paid_up = http("POST", f"/api/episodes/{paid}/vod-upload")
    assert paid_up[0] == 200, paid_up
    blocked = http("GET", f"/api/episodes/{paid}/vod-play?user_id=7")
    assert blocked[0] == 409, blocked
    print("PASS 还没转码完的付费集 409（付费与登录规则见 e2e.py）", flush=True)


if __name__ == "__main__":
    main()
