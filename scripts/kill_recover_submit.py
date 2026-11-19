#!/usr/bin/env python3
"""提交一个转码任务后立刻退出，供外面杀掉 worker 再观察恢复。"""
import json, subprocess, urllib.request

BASE = "http://nginx:8080"


def http(method, path, body=None, data=None):
    url = path if path.startswith("http") else BASE + path
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read()
        return json.loads(raw or b"{}")


def main():
    subprocess.run([
        "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30",
        "-t", "90", "-c:v", "libx264", "-pix_fmt", "yuv420p", "/tmp/k.mp4"], check=True)
    drama_id = http("POST", "/api/dramas", {"title": "kill-recover"})["drama_id"]
    episode_id = http("POST", f"/api/dramas/{drama_id}/episodes", {"ep_no": 1, "title": "k"})["episode_id"]
    upload = http("POST", f"/api/episodes/{episode_id}/upload-url")
    # 路线 C 起 upload-url 落到视频云，complete 会校验源文件类型，直传要声明 Content-Type
    put = urllib.request.Request(upload["put_url"], data=open("/tmp/k.mp4", "rb").read(), method="PUT",
                                 headers={"Content-Type": "video/mp4"})
    urllib.request.urlopen(put, timeout=60).read()
    done = http("POST", f"/api/assets/{upload['asset_id']}/complete")
    print("ASSET", upload["asset_id"], "EP", episode_id, "JOB", done["job_id"], flush=True)


if __name__ == "__main__":
    main()
