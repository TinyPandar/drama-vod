#!/usr/bin/env python3
"""短剧 Demo 端到端（路线 C：Demo 是视频云的客户）。需要 docker compose 栈、本机 ffmpeg/ffprobe，只用标准库。

  python3 scripts/e2e.py [--base http://localhost:8080]

覆盖：建剧建集 → 冻结的 upload-url / PUT 直传 / complete（现在都落到视频云，付费集是 Core 加密资产）→
Core 转码完成后 Demo 对账把每集切到 Core 资产 → 观众注册 / 登录 → 匿名只能看免费集、付费集 401 →
登录后未解锁 402 → 用金币解锁（扣费、重复解锁不重复扣、余额不够 402、不能替别人解锁）→
播放地址和 token 都由视频云签（/hls/v1/、/keys/v1/）→ hls.js 方式（Authorization 头）和 iPhone 原生方式
（native_master_url，不带任何头）都能用 ffmpeg 完整解码加密集 → 篡改签名 403、token 不对 401 → 下一集预加载 →
播放计数回刷。路线 C 之前的版本验的是旧链路（/hls/、/keys/{id}），旧链路已停用，见 git 历史。
"""
import argparse, json, os, re, subprocess, sys, tempfile, time, urllib.error, urllib.parse, urllib.request, uuid

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="http://localhost:8080")
ap.add_argument("--seconds", type=int, default=12)
ap.add_argument("--timeout", type=int, default=300)
args = ap.parse_args()
B = args.base
results = []


def http(method, path, body=None, headers=None, data=None):
    url = path if path.startswith("http") else B + path
    headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def J(resp):
    try:
        return json.loads(resp[1] or b"{}")
    except ValueError:
        return {}


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def wait(pred, timeout=args.timeout, every=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        v = pred()
        if v:
            return v
        time.sleep(every)
    return None


def auth(token):
    return {"Authorization": "Bearer " + token}


def decode(url, extra=()):
    p = subprocess.run(["ffmpeg", "-v", "error", *extra, "-i", url, "-f", "null", "-"], capture_output=True, text=True, timeout=300)
    return p.returncode == 0 and not p.stderr.strip(), p.stderr.strip()[:200]


tmp = tempfile.mkdtemp()
src = os.path.join(tmp, "e2e.mp4")
print("== 1. 生成竖屏测试视频", flush=True)
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", str(args.seconds),
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", src], check=True)
check("Demo API 健康", http("GET", "/api/v1/healthz")[0] == 200)

print("== 2. 建剧 + 3 集（第 1 集免费，第 2 集 30 金币，第 3 集 80 金币）", flush=True)
suffix = uuid.uuid4().hex[:6]
did = J(http("POST", "/api/dramas", {"title": f"e2e 剧 {suffix}"}))["drama_id"]
prices = {1: 0, 2: 30, 3: 80}
eps = {i: J(http("POST", f"/api/dramas/{did}/episodes",
                 {"ep_no": i, "title": f"第{i}集", "is_paid": prices[i] > 0, "price_coins": prices[i]}))["episode_id"]
       for i in (1, 2, 3)}

print("== 3. 冻结的上传接口（现在落到视频云）", flush=True)
assets = {}
for i, ep in eps.items():
    up = J(http("POST", f"/api/episodes/{ep}/upload-url"))
    check(f"第{i}集 upload-url 返回 Core 上传会话", up.get("asset_id") and "/vod-source/raw/t" in up.get("put_url", "")
          and up.get("expires_in"), {k: up.get(k) for k in ("asset_id", "expires_in")})
    if i == 1:
        early = http("POST", f"/api/assets/{up['asset_id']}/complete")
        check("没 PUT 就 complete → 409", early[0] == 409, early[0])
    # 路线 C：Core complete 会校验源文件类型，直传时要声明 Content-Type（浏览器用 File 上传会自动带上）
    st, _, _ = http("PUT", up["put_url"], data=open(src, "rb").read(), headers={"Content-Type": "video/mp4"})
    check(f"第{i}集直传 PUT", st == 200, st)
    done = http("POST", f"/api/assets/{up['asset_id']}/complete")
    check(f"第{i}集 complete 建任务", done[0] == 200 and J(done).get("job_id"), done[1][:120])
    assets[i] = (up["asset_id"], J(done).get("job_id"))
again = J(http("POST", f"/api/assets/{assets[1][0]}/complete"))
check("重复 complete 返回同一个任务", again.get("job_id") == assets[1][1], again)

print("== 4. Core 转码 → Demo 对账切换", flush=True)
for i, (aid, _) in assets.items():
    ok = wait(lambda: J(http("GET", f"/api/assets/{aid}")).get("asset", {}).get("status") == "READY")
    check(f"第{i}集 Core 资产 READY", ok)
st = J(http("GET", f"/api/assets/{assets[2][0]}"))
check("GET /api/assets 冻结字段（asset.status、jobs、renditions）且付费集是加密资产",
      st.get("asset", {}).get("encrypted") == 1 and isinstance(st.get("jobs"), list) and "renditions" in st, st.get("asset"))
switched = wait(lambda: all(e["status"] == "READY" and e.get("core_asset_id") == assets[e["ep_no"]][0]
                            for e in J(http("GET", f"/api/dramas/{did}")).get("episodes", [])), 60)
check("三集都切到了各自的 Core 资产", switched)
listed = [d for d in J(http("GET", "/api/dramas")).get("dramas", []) if d["id"] == did]
check("剧列表 ready_episodes = 3", listed and listed[0]["ready_episodes"] == 3, listed)

print("== 5. 观众账号", flush=True)
alice = J(http("POST", "/api/auth/register", {"username": f"alice{suffix}", "password": "alice-pass-1"}))
bob = J(http("POST", "/api/auth/register", {"username": f"bob{suffix}", "password": "bob-pass-12"}))
check("注册返回会话和演示金币", alice.get("token", "").startswith("dsess1.") and alice.get("user", {}).get("coins", 0) >= 80, alice.get("user"))
check("用户名重复 409", http("POST", "/api/auth/register", {"username": f"alice{suffix}", "password": "whatever-1"})[0] == 409)
check("口令错误 401", http("POST", "/api/auth/login", {"username": f"alice{suffix}", "password": "nope-nope"})[0] == 401)
login = J(http("POST", "/api/auth/login", {"username": f"alice{suffix}", "password": "alice-pass-1"}))
check("登录成功", login.get("user", {}).get("id") == alice["user"]["id"])
A, Bt, aid_user = auth(login["token"]), auth(bob["token"]), alice["user"]["id"]
coins0 = alice["user"]["coins"]

print("== 6. 匿名与付费", flush=True)
free = http("GET", f"/api/episodes/{eps[1]}/play")
fj = J(free)
check("匿名可看免费集，地址由视频云签", free[0] == 200 and fj.get("via") == "vod-api" and fj.get("master_url", "").startswith("/hls/v1/")
      and fj.get("encrypted") is False, fj.get("master_url"))
check("匿名时下一集（付费）只给 locked", fj.get("next") == {"episode_id": eps[2], "locked": True}, fj.get("next"))
check("匿名看付费集 401", http("GET", f"/api/episodes/{eps[2]}/play")[0] == 401)
check("只带 ?user_id= 不算登录（401）", http("GET", f"/api/episodes/{eps[2]}/play?user_id={aid_user}")[0] == 401)
check("登录后未解锁 402", http("GET", f"/api/episodes/{eps[2]}/play", headers=A)[0] == 402)
check("会话和 user_id 对不上 403", http("GET", f"/api/episodes/{eps[2]}/play?user_id={aid_user + 999}", headers=A)[0] == 403)

print("== 7. 金币解锁", flush=True)
check("不能替别人解锁（403）", http("POST", f"/api/users/{aid_user}/unlock", {"episode_id": eps[2]}, headers=Bt)[0] == 403)
check("没登录不能解锁（401）", http("POST", f"/api/users/{aid_user}/unlock", {"episode_id": eps[2]})[0] == 401)
u1 = J(http("POST", f"/api/users/{aid_user}/unlock", {"episode_id": eps[2]}, headers=A))
check("解锁第 2 集扣 30 金币", u1.get("charged") == 30 and u1.get("coins") == coins0 - 30, u1)
u2 = J(http("POST", f"/api/users/{aid_user}/unlock", {"episode_id": eps[2]}, headers=A))
check("重复解锁不重复扣", u2.get("charged") == 0 and u2.get("coins") == coins0 - 30, u2)
poor = http("POST", f"/api/users/{aid_user}/unlock", {"episode_id": eps[3]}, headers=A)
need_more = coins0 - 30 < 80
if need_more:
    check("余额不够 402 insufficient_coins，什么都不扣", poor[0] == 402 and J(poor).get("code") == "insufficient_coins", poor[1][:120])
me = J(http("GET", "/api/me", headers=A))
check("/api/me 余额与已解锁的集", me.get("user", {}).get("coins") == coins0 - 30 - (0 if need_more else 80)
      and eps[2] in me.get("unlocked", []), me)

print("== 8. 付费加密集播放（全部经视频云）", flush=True)
paid = J(http("GET", f"/api/episodes/{eps[2]}/play", headers=A))
check("解锁后可播，encrypted 取 Core 的真实值", paid.get("encrypted") is True and paid.get("token", "").startswith("vpt1.")
      and paid.get("native_master_url"), {k: paid.get(k) for k in ("asset_id", "encrypted", "via")})
master = http("GET", paid["master_url"])
media_rel = [l for l in master[1].decode().splitlines() if l and not l.startswith("#")][0]
base = paid["master_url"].rsplit("/", 1)[0] + "/"
media = http("GET", base + media_rel)[1].decode()
m = re.search(r'URI="([^"]+)"', media)
check("媒体列表带 /keys/v1/ 的 AES-128 key", m and m.group(1).startswith("/keys/v1/"), m and m.group(1))
key_url = m.group(1)
check("不带 token 拿 key → 401", http("GET", key_url)[0] == 401)
k = http("GET", key_url, headers={"Authorization": "Bearer " + paid["token"]})
check("Core token 拿到 16 字节 key、no-store", k[0] == 200 and len(k[1]) == 16 and "no-store" in k[2].get("Cache-Control", ""))
check("免费集的 token 拿不到付费集的 key", http("GET", key_url, headers={"Authorization": "Bearer " + fj["token"]})[0] == 401)
ok, err = decode(B + paid["master_url"], ["-headers", f"Authorization: Bearer {paid['token']}\r\n"])
check("hls.js 方式（带 Authorization 头）ffmpeg 完整解码", ok, err)
ok, err = decode(B + paid["native_master_url"])
check("iPhone 原生方式（native_master_url，不带任何头）ffmpeg 完整解码", ok, err)
nat = http("GET", paid["native_master_url"].replace("token=", "token=x"))
check("原生列表 token 不对 → 401", nat[0] == 401, nat[0])
ok, _ = decode(B + paid["master_url"])
check("不带 token 播加密集失败", not ok)
sig = paid["master_url"].split("/")[3]
check("篡改签名 → 403", http("GET", paid["master_url"].replace(sig, sig[:-2] + ("AA" if sig[-2:] != "AA" else "BB")))[0] == 403)
ok, err = decode(B + fj["master_url"])
check("免费集完整解码", ok, err)
p1 = J(http("GET", f"/api/episodes/{eps[1]}/play", headers=A))
check("已解锁时下一集带播放地址（供预加载）", (p1.get("next") or {}).get("master_url", "").startswith("/hls/v1/"), p1.get("next"))

print("== 9. 播放计数", flush=True)
before = J(http("GET", f"/api/episodes/{eps[1]}/stats"))
http("POST", "/api/admin/flush-play-counts")
after = J(http("GET", f"/api/episodes/{eps[1]}/stats"))
check("回刷后计数进 MySQL", after.get("flushed_to_mysql", 0) >= 2 and after.get("pending_in_redis") == 0, f"{before} -> {after}")

passed = sum(1 for _, ok in results if ok)
print(f"\n== 结果：{passed}/{len(results)} 通过", flush=True)
sys.exit(0 if passed == len(results) else 1)
