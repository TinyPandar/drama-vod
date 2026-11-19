#!/usr/bin/env python3
"""短剧播放页（nginx/html/index.html）在真实浏览器里的验收（Playwright + Chromium，需要 compose 栈和本机 ffmpeg）。

  python3 scripts/player_live.py          # 可用 CHROME_BIN 指向本机 Chrome / Chromium

先用 Demo 接口建一部剧（第 1 集免费、第 2 集 30 金币），上传并等它们切到视频云；然后只通过页面操作：
匿名看免费集真的在播；打开付费集提示登录 → 在页头注册 → 自动重试并提示需要解锁 → 点「解锁并播放」扣 30 金币、
页头余额变化 → 加密集真的在播（hls.js 带 Authorization 取 /keys/v1/）→ 退出后再看付费集又要求登录；全程无脚本错误。
"""
import json, os, re, subprocess, sys, tempfile, time, urllib.error, urllib.request, uuid

from playwright.sync_api import sync_playwright

B = os.environ.get("VOD_BASE", "http://localhost:8080")
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def http(method, path, body=None, data=None, headers=None):
    headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(path if path.startswith("http") else B + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {}


def setup():
    src = os.path.join(tempfile.mkdtemp(), "p.mp4")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30", "-f", "lavfi",
                    "-i", "sine=frequency=330", "-t", "20", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                    "-shortest", src], check=True)
    did = http("POST", "/api/dramas", {"title": f"播放页验收 {uuid.uuid4().hex[:6]}"})[1]["drama_id"]
    eps = {}
    for no, price in ((1, 0), (2, 30)):
        ep = http("POST", f"/api/dramas/{did}/episodes", {"ep_no": no, "title": f"第{no}集", "is_paid": price > 0,
                                                          "price_coins": price})[1]["episode_id"]
        up = http("POST", f"/api/episodes/{ep}/upload-url")[1]
        http("PUT", up["put_url"], data=open(src, "rb").read(), headers={"Content-Type": "video/mp4"})
        assert http("POST", f"/api/assets/{up['asset_id']}/complete")[0] == 200
        eps[no] = ep
    deadline = time.time() + 300
    while time.time() < deadline:
        rows = http("GET", f"/api/dramas/{did}")[1].get("episodes", [])
        if rows and all(e["status"] == "READY" and e.get("core_asset_id") for e in rows):
            return eps
        time.sleep(3)
    raise SystemExit("episodes never switched to Core")


def main():
    eps = setup()
    print("测试剧集就绪", eps, flush=True)
    errors = []
    with sync_playwright() as pw:
        launch = {"headless": True, "args": ["--autoplay-policy=no-user-gesture-required"]}
        if os.environ.get("CHROME_BIN"):
            launch["executable_path"] = os.environ["CHROME_BIN"]
        browser = pw.chromium.launch(**launch)
        page = browser.new_page(viewport={"width": 1280, "height": 800})
        page.on("pageerror", lambda e: errors.append(str(e)))

        def playing(timeout=60000):
            try:
                page.wait_for_function("() => { const v = document.getElementById('v'); return v && v.currentTime > 1 }",
                                       timeout=timeout)
                return True
            except Exception:
                return False

        page.goto(f"{B}/?ep={eps[1]}")
        check("匿名观众看免费集，真的在播", playing())
        check("页头显示「登录」", page.locator("#uid-btn").inner_text().strip() == "登录")

        page.goto(f"{B}/?ep={eps[2]}")
        page.locator("#gate").wait_for(state="visible", timeout=30000)
        check("付费集提示登录", "登录" in page.locator("#gate-title").inner_text(), page.locator("#gate-title").inner_text())
        page.locator("#gate-go").click()
        user = f"web{uuid.uuid4().hex[:8]}"
        page.fill("#auth-user", user)
        page.fill("#auth-pass", "player-pass-1")
        page.locator("#auth-register").click()
        page.wait_for_function("() => /金币/.test(document.getElementById('uid-btn').textContent)", timeout=15000)
        coins_before = int(re.search(r"(\d+) 金币", page.locator("#uid-btn").inner_text()).group(1))
        check("注册后页头显示用户名和金币", user in page.locator("#uid-btn").inner_text(), page.locator("#uid-btn").inner_text())
        page.wait_for_function("() => /解锁/.test(document.getElementById('gate-title').textContent)", timeout=15000)
        check("登录后自动重试，提示需要解锁并写明价格", "30 金币" in page.locator("#gate-sub").inner_text(),
              page.locator("#gate-sub").inner_text())
        page.locator("#gate-go").click()
        check("解锁后加密集真的在播（hls.js 带 token 取 key）", playing())
        page.wait_for_function(f"() => document.getElementById('uid-btn').textContent.includes('{coins_before - 30} 金币')", timeout=10000)
        check("页头余额扣了 30", True)
        log = page.locator("#log").inner_text()
        check("播放走视频云签名地址", "拿到签名地址 /hls/v1/" in log, log[-200:])

        check("观看页也有账号入口并显示余额", f"{coins_before - 30} 金币" in page.locator("#watch-acct").inner_text())
        page.locator("#watch-acct").click()
        page.locator("#auth-logout").click()
        page.goto(f"{B}/?ep={eps[2]}")
        page.locator("#gate").wait_for(state="visible", timeout=30000)
        check("退出后再看付费集又要求登录", "登录" in page.locator("#gate-title").inner_text())
        check("全程无页面脚本错误", not errors, "; ".join(errors[:3]))
        browser.close()
    passed = sum(1 for _, ok in results if ok)
    print(f"\n== 结果：{passed}/{len(results)} 通过", flush=True)
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
