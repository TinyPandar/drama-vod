#!/usr/bin/env python3
"""端到端验证：生成测试视频 -> 预签名直传 -> 转码 -> 签名播放 -> 缓存命中 -> 鉴权拒绝 -> ffmpeg 真解码。
只依赖 python3 标准库 + 本机 ffmpeg/ffprobe。用法：python3 scripts/e2e.py [--base http://localhost:8080]"""
import argparse, json, os, re, subprocess, sys, tempfile, time, urllib.error, urllib.request

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="http://localhost:8080")
ap.add_argument("--seconds", type=int, default=20)
args = ap.parse_args()
B = args.base
results = []


def http(method, path, body=None, headers=None, data=None):
    url = path if path.startswith("http") else B + path
    if body is not None:
        data = json.dumps(body).encode()
        headers = {**(headers or {}), "Content-Type": "application/json"}
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def J(resp):
    return json.loads(resp[1])


def check(name, ok, detail=""):
    results.append((name, ok))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    return ok


tmp = tempfile.mkdtemp()
src = os.path.join(tmp, "test.mp4")
print("== 1. 用 ffmpeg 生成竖屏测试视频", src)
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", str(args.seconds),
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", src], check=True)

print("== 2. 建剧 + 3 集（第 1、2 集免费，第 3 集付费）")
did = J(http("POST", "/api/dramas", {"title": f"e2e 测试剧 {int(time.time())}"}))["drama_id"]
eps = [J(http("POST", f"/api/dramas/{did}/episodes", {"ep_no": i, "title": f"第{i}集", "is_paid": i == 3}))["episode_id"]
       for i in (1, 2, 3)]
print("   drama", did, "episodes", eps)

print("== 3. 每集：申请预签名 URL -> PUT 直传 -> complete 回调")
assets = {}
for ep in eps:
    up = J(http("POST", f"/api/episodes/{ep}/upload-url"))
    st, _, _ = http("PUT", up["put_url"], data=open(src, "rb").read())
    check(f"ep{ep} 预签名直传 PUT", st == 200, f"HTTP {st}")
    c = http("POST", f"/api/assets/{up['asset_id']}/complete")
    check(f"ep{ep} complete 回调建任务", c[0] == 200, c[1].decode()[:120])
    assets[ep] = up["asset_id"]
# 幂等：重复回调不会产生第二个任务
c2 = J(http("POST", f"/api/assets/{assets[eps[0]]}/complete"))
check("重复 complete 回调幂等(同一 job_id)", c2["job_id"] == J(http("GET", f"/api/assets/{assets[eps[0]]}"))["jobs"][0]["id"]
      and len(J(http("GET", f"/api/assets/{assets[eps[0]]}"))["jobs"]) == 1)
# 未上传就回调要被拒
bad = J(http("POST", f"/api/episodes/{eps[0]}/upload-url"))
check("未上传就 complete 被拒(409)", http("POST", f"/api/assets/{bad['asset_id']}/complete")[0] == 409)

print("== 4. 等转码完成")
t0 = time.time()
while time.time() - t0 < 300:
    sts = {ep: J(http("GET", f"/api/assets/{a}"))["asset"]["status"] for ep, a in assets.items()}
    if all(s in ("READY", "FAILED") for s in sts.values()):
        break
    time.sleep(3)
print("   状态", sts, f"等待 {time.time() - t0:.0f}s")
check("3 集都转码成功", all(s == "READY" for s in sts.values()))
info = J(http("GET", f"/api/assets/{assets[eps[0]]}"))
print("   码率档", [(x["name"], f"{x['width']}x{x['height']}", x["video_kbps"]) for x in info["renditions"]])

print("== 5. 播放免费集")
p = http("GET", f"/api/episodes/{eps[0]}/play?user_id=1001")
check("免费集拿到签名播放地址", p[0] == 200)
p = J(p)
print("   master_url", p["master_url"])
check("响应里带下一集(预加载用)", p["next"] and p["next"]["episode_id"] == eps[1])
st, master, h1 = http("GET", p["master_url"])
check("master.m3u8 首次请求 MISS", st == 200 and h1.get("X-Cache-Status") == "MISS", h1.get("X-Cache-Status"))
st, _, h2 = http("GET", p["master_url"])
check("master.m3u8 再次请求 HIT(nginx 缓存)", h2.get("X-Cache-Status") == "HIT", h2.get("X-Cache-Status"))
p_other = J(http("GET", f"/api/episodes/{eps[0]}/play?user_id=2002"))
st, _, h3 = http("GET", p_other["master_url"])
check("另一用户不同签名也命中同一份缓存", h3.get("X-Cache-Status") == "HIT", h3.get("X-Cache-Status"))
master = master.decode()
print("   master.m3u8:\n" + "\n".join("     " + l for l in master.strip().splitlines()))
variant = [l for l in master.splitlines() if l and not l.startswith("#")][0]
base = p["master_url"].rsplit("/", 1)[0] + "/"
media = http("GET", base + variant)[1].decode()
durs = [float(x) for x in re.findall(r"#EXTINF:([\d.]+)", media)]
check("首片短(1s)、后续 4s", durs[0] <= 1.01 and all(abs(d - 4) < 0.05 for d in durs[1:-1]), f"分片时长 {durs}")
seg0 = [l for l in media.splitlines() if l and not l.startswith("#")][0]
st, _, _ = http("GET", base + variant.rsplit("/", 1)[0] + "/" + seg0)
check("分片可通过签名前缀访问", st == 200)

print("== 6. 防盗链")
sig, exp = p["master_url"].split("/")[2:4]
tampered = p["master_url"].replace(sig, sig[:-2] + ("AA" if not sig.endswith("AA") else "BB"))
check("篡改签名 -> 403", http("GET", tampered)[0] == 403)
other_asset = p["master_url"].replace(f"/{p['asset_id']}/", f"/{assets[eps[1]]}/")
check("拿 A 资产的签名访问 B 资产 -> 403", http("GET", other_asset)[0] == 403)
expired = p["master_url"].replace(f"/{exp}/", f"/{int(time.time()) - 10}/")
check("改过期时间 -> 403", http("GET", expired)[0] == 403)

print("== 7. 付费集 + AES-128")
p3 = http("GET", f"/api/episodes/{eps[2]}/play?user_id=1001")
check("未解锁付费集 -> 402", p3[0] == 402)
http("POST", "/api/users/1001/unlock", {"episode_id": eps[2]})
p3 = http("GET", f"/api/episodes/{eps[2]}/play?user_id=1001")
check("解锁后拿到播放地址", p3[0] == 200)
p3 = J(p3)
m3 = http("GET", p3["master_url"])[1].decode()
v3 = [l for l in m3.splitlines() if l and not l.startswith("#")][0]
b3 = p3["master_url"].rsplit("/", 1)[0] + "/"
media3 = http("GET", b3 + v3)[1].decode()
keyline = next(l for l in media3.splitlines() if l.startswith("#EXT-X-KEY"))
print("   ", keyline)
check("付费集 m3u8 带 AES-128 KEY 标签", "METHOD=AES-128" in keyline)
key_uri = re.search(r'URI="([^"]+)"', keyline).group(1)
check("无 token 请求 key -> 401", http("GET", key_uri)[0] == 401)
check("用 1 号资产的 token 拿 3 号资产 key -> 401",
      http("GET", key_uri, headers={"Authorization": "Bearer " + p["token"]})[0] == 401)
st, key, _ = http("GET", key_uri, headers={"Authorization": "Bearer " + p3["token"]})
check("合法 token 拿到 16 字节 key", st == 200 and len(key) == 16)
check("免费集 m3u8 不加密", "EXT-X-KEY" not in media)

print("== 8. ffmpeg 当播放器：经 nginx 拉流并完整解码")
for label, pi in (("免费集", p), ("付费加密集", p3)):
    cmd = ["ffmpeg", "-v", "error", "-headers", f"Authorization: Bearer {pi['token']}\r\n",
           "-i", B + pi["master_url"], "-map", "0:v:0", "-f", "null", "-"]
    t = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True)
    check(f"{label} 完整解码无错误", r.returncode == 0 and not r.stderr.strip(), (r.stderr.strip()[:200] or "ok"))
plain = subprocess.run(["ffmpeg", "-v", "error", "-i", B + p3["master_url"], "-map", "0:v:0", "-t", "2",
                        "-f", "null", "-"], capture_output=True, text=True)
check("不带 token 播放加密集失败", plain.returncode != 0 or "error" in plain.stderr.lower(),
      plain.stderr.strip().splitlines()[0][:150] if plain.stderr.strip() else "")

print("== 9. 播放计数：Redis 实时 -> MySQL 回刷")
s1 = J(http("GET", f"/api/episodes/{eps[0]}/stats"))
http("POST", "/api/admin/flush-play-counts")
s2 = J(http("GET", f"/api/episodes/{eps[0]}/stats"))
check("回刷后计数进 MySQL", s2["flushed_to_mysql"] >= s1["pending_in_redis"] > 0, f"{s1} -> {s2}")
print("   热榜", J(http("GET", "/api/rank/hot")))

ok = sum(1 for _, x in results if x)
print(f"\n== 结果：{ok}/{len(results)} 通过")
sys.exit(0 if ok == len(results) else 1)
