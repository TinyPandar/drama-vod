#!/usr/bin/env python3
"""管理控制台在真实后端下的浏览器验收（Playwright + Chromium，需要 docker compose 栈和本机 ffmpeg）。在仓库根目录运行：

  pip install playwright && playwright install chromium     # 或者用 CHROME_BIN 指向本机 Chrome / Chromium
  python3 scripts/console_live.py [--shots DIR]

管理员账号来自 VOD_ADMIN_USERNAME / VOD_ADMIN_PASSWORD（环境变量或 .env）。脚本只通过界面操作，
不直接调接口改数据（只在最后用无效身份直接请求接口，核对服务端仍然拒绝）。

覆盖：未登录跳登录页、口令错误、登录后回原页；建租户、停用/启用、改配额、新建 Key 只显示一次并「在本标签页使用」、
页头不出现明文；新建租户模板；未选 Key 时租户页给引导；小文件直传、大文件分片中断后续传、分片上传取消；
资产详情封面与试播（浏览器里真的播放）、按模板转码；任务详情重转、取消、重试；Webhook 登记、签名密钥只显示一次、
停用/启用、投递记录；用量与配额；模板页；轮换后旧 Key 失效引导、管理登录仍有效、换上新 Key；撤销后失效；
管理登录过期回登录页且 Key 保留；退出；375 宽抽屉开合与无横向溢出；全程无页面脚本错误。
--shots 给目录时在 1440×900 / 1280×800 / 375×812 下截图，一次性密钥区域会被打码。
只报告真实跑出的结果。
"""
import argparse, os, pathlib, re, subprocess, sys, tempfile, time, urllib.error, urllib.request, uuid

from playwright.sync_api import sync_playwright, expect

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
CONSOLE = BASE + "/vod-console/"
TIMEOUT = int(os.environ.get("CONSOLE_LIVE_TIMEOUT", "300"))
results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"{'PASS' if ok else 'FAIL'} {name}" + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def ffmpeg(path, seconds, size, bitrate):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc2=size={size}:rate=30",
                    "-f", "lavfi", "-i", "sine=frequency=440", "-t", str(seconds), "-c:v", "libx264", "-b:v", bitrate,
                    "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", str(path)], check=True)


def raw_status(path, headers):
    req = urllib.request.Request(BASE + path, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shots", help="截图目录（不传就不截图）")
    ap.add_argument("--headed", action="store_true")
    args = ap.parse_args()
    user, password = dotenv("VOD_ADMIN_USERNAME"), dotenv("VOD_ADMIN_PASSWORD")
    if not user or not password:
        sys.exit("需要 VOD_ADMIN_USERNAME / VOD_ADMIN_PASSWORD（环境变量或 .env）")
    shots = pathlib.Path(args.shots) if args.shots else None
    if shots:
        shots.mkdir(parents=True, exist_ok=True)
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="console-live-"))
    small, big = tmp / "small.mp4", tmp / "big.mp4"
    ffmpeg(small, 6, "720x1280", "2M")
    ffmpeg(big, 20, "1280x720", "8M")  # 约 20 MB，默认 8 MiB 一片 → 3 片
    print(f"测试视频 small={small.stat().st_size} big={big.stat().st_size} 字节", flush=True)
    suffix = uuid.uuid4().hex[:6]
    errors = []

    with sync_playwright() as pw:
        launch = {"headless": not args.headed}
        if os.environ.get("CHROME_BIN"):
            launch["executable_path"] = os.environ["CHROME_BIN"]
        browser = pw.chromium.launch(**launch, args=["--autoplay-policy=no-user-gesture-required"])
        ctx = browser.new_context(viewport={"width": 1440, "height": 900}, locale="zh-CN")
        ctx.set_default_timeout(30000)
        page = ctx.new_page()
        page.on("pageerror", lambda e: errors.append(str(e)))
        dialogs = []
        page.on("dialog", lambda d: (dialogs.append(d.message), d.accept()))

        def go(hash_path):
            page.goto(CONSOLE + "#" + hash_path)
            page.wait_for_load_state("networkidle")

        def seen(locator, timeout=15000):
            try:
                locator.first.wait_for(state="visible", timeout=timeout)
                return True
            except Exception:
                return False

        def body():
            return page.locator("body").inner_text()

        def shot(name, full=True):
            if not shots:
                return
            masks = [page.locator(".secret-val")]
            for w, h in ((1440, 900), (1280, 800), (375, 812)):
                page.set_viewport_size({"width": w, "height": h})
                page.wait_for_timeout(300)
                page.screenshot(path=str(shots / f"{name}-{w}.png"), full_page=full, mask=masks)
            page.set_viewport_size({"width": 1440, "height": 900})

        def refresh_until(pred, what, timeout=TIMEOUT):
            deadline = time.time() + timeout
            while time.time() < deadline:
                if pred():
                    return True
                page.get_by_role("button", name="刷新", exact=True).first.click()
                page.wait_for_load_state("networkidle")
                page.wait_for_timeout(2000)
            return check(f"等待 {what}", False, "超时")

        # ---------- 1. 登录 ----------
        print("== 1. 未登录与管理登录", flush=True)
        go("/tenants")
        check("未登录打开「租户与 Key」去登录页", "#/login" in page.url and seen(page.get_by_role("heading", name="管理登录", exact=True)),
              page.url)
        shot("login")
        page.fill("#u", user)
        page.fill("#p", password + "x")
        page.get_by_role("button", name="登录", exact=True).click()
        err = page.locator(".alert.err")
        expect(err).to_be_visible()
        check("口令错误给出中文提示", "401" not in err.locator(".a-title").inner_text(), err.locator(".a-title").inner_text())
        page.fill("#p", password)
        page.get_by_role("button", name="登录", exact=True).click()
        page.wait_for_url(re.compile(r"#/tenants$"))
        check("登录后回到「租户与 Key」并高亮", page.locator("nav .item.on").first.inner_text().strip().startswith("租户与 Key"))
        check("页头显示管理员用户名", user in page.locator("header .who.admin").inner_text())

        # ---------- 2. 未选 Key 的租户页 ----------
        print("== 2. 未选 Key 时的引导", flush=True)
        for path, title in (("/assets", "资产"), ("/usage", "用量与配额")):
            go(path)
            text = body()
            check(f"未选 Key 打开「{title}」出现引导、没有 401", "未选用租户 Key" in text and "401" not in text
                  and "unauthorized" not in text.lower())
        shot("assets-no-key")

        # ---------- 3. 租户、配额、Key、模板 ----------
        print("== 3. 租户与 Key", flush=True)
        go("/tenants")
        tname = f"console-live-{suffix}"
        page.fill("#n", tname)
        page.get_by_role("button", name="创建租户", exact=True).click()
        row = page.locator("tr", has_text=tname)
        expect(row).to_be_visible()
        check("创建租户出现在列表", seen(row))
        row.get_by_role("button", name="停用", exact=True).click()
        expect(row.get_by_role("button", name="启用", exact=True)).to_be_visible()
        check("停用租户", "DISABLED" in row.inner_text() or "停用" in row.inner_text())
        row.get_by_role("button", name="启用", exact=True).click()
        expect(row.get_by_role("button", name="停用", exact=True)).to_be_visible()
        check("重新启用租户", True)
        shot("tenants")
        row.get_by_role("link", name="管理 Key", exact=True).click()
        page.wait_for_url(re.compile(r"#/tenants/\d+$"))
        tenant_id = page.url.rsplit("/", 1)[1]
        page.fill("#q2", "2")
        page.fill("#q3", "50")
        page.get_by_role("button", name="保存配额", exact=True).click()
        page.wait_for_load_state("networkidle")
        page.reload()
        page.wait_for_load_state("networkidle")
        check("配额保存后刷新仍在", page.input_value("#q2") == "2" and page.input_value("#q3") == "50",
              f"{page.input_value('#q2')} {page.input_value('#q3')}")
        page.fill("#kn", "console-live")
        page.get_by_role("button", name="新建 Key", exact=True).click()
        secret = page.locator(".secret-val")
        expect(secret).to_be_visible()
        key1 = secret.inner_text().strip()
        check("新 Key 明文只在创建后显示一次", len(key1) >= 20)
        shot("tenant-detail-new-key")
        page.get_by_role("button", name="在本标签页使用这把 Key", exact=True).click()
        page.wait_for_load_state("networkidle")
        check("页头出现租户 Key 标识", seen(page.locator("header .who.tenant")))
        check("「在本标签页使用」后跳到资产页", page.url.endswith("#/assets"), page.url)
        check("整页不含 Key 明文", key1 not in body())
        go(f"/tenants/{tenant_id}")
        page.fill("#t1", f"tpl_{suffix}")
        page.fill("#t2", '[["360p",360,600]]')
        page.fill("#t3", "64")
        page.get_by_role("button", name="新建租户模板", exact=True).click()
        expect(page.locator("tr", has_text=f"tpl_{suffix}")).to_be_visible()
        check("新建租户模板出现在列表", True)

        # ---------- 4. 上传 ----------
        print("== 4. 资产与上传", flush=True)
        go("/assets")
        check("选用 Key 后资产页可用", seen(page.get_by_role("heading", name="上传视频", exact=True)))

        def asset_ids():
            hrefs = page.locator("a[href^='#/assets/']").evaluate_all("els => els.map(e => e.getAttribute('href'))")
            return set(int(h.rsplit("/", 1)[1]) for h in hrefs)

        before = asset_ids()
        page.set_input_files("input[type=file]", str(small))
        refresh_until(lambda: len(asset_ids() - before) >= 1, "小文件出现在资产列表", 120)
        small_id = max(asset_ids() - before)
        check("小文件直传后出现新资产", small_id > 0, f"#{small_id}")

        # 分片：点「继续上传」之前，第 2 片的请求全部中断（上传代码每片自带几次重试），之后放行
        state = {"dropped": 0, "block": True}

        def flaky(route):
            if "partNumber=2" in route.request.url and state["block"]:
                state["dropped"] += 1
                return route.abort()
            return route.continue_()

        page.route(re.compile(r".*uploadId=.*"), flaky)
        before = asset_ids()
        page.set_input_files("input[type=file]", str(big))
        interrupted = page.get_by_text("分片上传中断，还没传完")
        expect(interrupted).to_be_visible(timeout=120000)
        check("分片中途失败后提示可续传", state["dropped"] >= 1, f"第 2 片被中断 {state['dropped']} 次")
        shot("assets-interrupted")
        state["block"] = False
        page.get_by_role("button", name="继续上传", exact=True).click()
        expect(interrupted).to_be_hidden(timeout=180000)
        page.unroute(re.compile(r".*uploadId=.*"))
        refresh_until(lambda: len(asset_ids() - before) >= 1, "分片资产出现在列表", 120)
        big_id = max(asset_ids() - before)
        check("续传完成并建出资产", big_id > 0, f"#{big_id}")

        # 分片上传取消：所有分片都失败 → 中断 → 取消
        page.route(re.compile(r".*uploadId=.*"), lambda r: r.abort())
        page.set_input_files("input[type=file]", str(big))
        expect(interrupted).to_be_visible(timeout=120000)
        page.get_by_role("button", name="取消上传", exact=True).click()
        expect(interrupted).to_be_hidden(timeout=30000)
        page.unroute(re.compile(r".*uploadId=.*"))
        check("分片上传可取消", True)

        # ---------- 5. 资产详情、试播、按模板转码 ----------
        print("== 5. 资产详情与试播", flush=True)
        go(f"/assets/{small_id}")
        refresh_until(lambda: "READY" in page.locator("h2").first.inner_text(), f"资产 #{small_id} READY")
        check("资产详情显示封面", page.locator("img").count() >= 1 and page.locator("img").first.evaluate("i => i.naturalWidth") > 0)
        page.get_by_role("button", name="申请播放 token 并试播", exact=True).click()
        video = page.locator("video.player")
        expect(video).to_be_visible()
        page.wait_for_function("() => { const v = document.querySelector('video.player'); return v && v.currentTime > 1 }",
                               timeout=60000)
        check("浏览器里真的播放（currentTime > 1s）", True)
        shot("asset-detail")
        page.select_option("#tpl", f"tpl_{suffix}")
        page.get_by_role("button", name="发起转码", exact=True).click()
        page.wait_for_url(re.compile(r"#/jobs/\d+$"))
        check("发起转码后跳到新任务详情", f"tpl_{suffix}" in body())
        refresh_until(lambda: "SUCCEEDED" in page.locator("h2").first.inner_text(), "按模板转码成功")
        check("按模板转码成功", "SUCCEEDED" in page.locator("h2").first.inner_text())

        # ---------- 6. 任务 ----------
        print("== 6. 任务：重转、取消、重试", flush=True)
        job_path = page.url.split("#", 1)[1]
        go("/jobs")
        page.locator("tbody tr").first.wait_for()
        n_jobs = page.locator("tbody tr").count()
        check("任务列表有本租户的 3 个任务（直传、分片、按模板）", n_jobs == 3, n_jobs)
        shot("jobs")
        go(job_path)
        page.get_by_role("button", name="按同一模板重新转码", exact=True).click()
        page.wait_for_load_state("networkidle")
        expect(page.get_by_role("button", name="取消任务", exact=True)).to_be_enabled(timeout=20000)
        check("按同一模板重新转码后任务进行中", True)
        page.get_by_role("button", name="取消任务", exact=True).click()
        page.wait_for_load_state("networkidle")
        refresh_until(lambda: "CANCELLED" in page.locator("h2").first.inner_text(), "任务取消", 60)
        check("取消任务", "CANCELLED" in page.locator("h2").first.inner_text())
        page.get_by_role("button", name="重试", exact=True).click()
        page.wait_for_load_state("networkidle")
        refresh_until(lambda: "SUCCEEDED" in page.locator("h2").first.inner_text(), "重试后成功")
        check("重试后任务成功", "SUCCEEDED" in page.locator("h2").first.inner_text())
        shot("job-detail")

        # ---------- 7. Webhook ----------
        print("== 7. Webhook", flush=True)
        go("/webhooks")
        page.fill("#u", f"https://example.com/console-live/{suffix}")
        page.get_by_role("button", name="登记", exact=True).click()
        expect(secret).to_be_visible()
        hook_secret = secret.inner_text().strip()
        check("登记后签名密钥只显示一次", len(hook_secret) >= 16)
        shot("webhooks")
        page.reload()
        page.wait_for_load_state("networkidle")
        check("刷新后不再显示签名密钥", hook_secret not in body())
        hrow = page.locator("tr", has_text=f"console-live/{suffix}")
        hrow.get_by_role("button", name="停用", exact=True).click()
        expect(hrow.get_by_role("button", name="启用", exact=True)).to_be_visible()
        hrow.get_by_role("button", name="启用", exact=True).click()
        expect(hrow.get_by_role("button", name="停用", exact=True)).to_be_visible()
        check("Webhook 停用 / 启用", True)
        check("投递记录区块存在", seen(page.locator("#del-title")))

        # ---------- 8. 用量、模板 ----------
        print("== 8. 用量与模板", flush=True)
        go("/usage")
        page.fill("#days", "3")
        page.get_by_role("button", name="查看", exact=True).click()
        page.wait_for_load_state("networkidle")
        check("用量与配额页显示配额和历史", "配额" in body() and page.locator("table").count() >= 1)
        shot("usage")
        go("/templates")
        check("模板页同时有系统模板和租户模板", "ladder_v1" in body() and f"tpl_{suffix}" in body())

        # ---------- 9. 轮换、撤销、失效引导 ----------
        print("== 9. Key 失效与管理登录过期", flush=True)
        go(f"/tenants/{tenant_id}")
        krow = page.locator("tr", has_text="console-live").first
        krow.get_by_role("button", name="轮换", exact=True).click()
        expect(secret).to_be_visible()
        key2 = secret.inner_text().strip()
        check("轮换后新明文显示一次且不同", key2 and key2 != key1)
        page.get_by_role("button", name="我已保存，关闭", exact=True).click()
        go("/assets")
        check("旧 Key 被拒绝后出现失效引导", seen(page.get_by_role("heading", name="当前选用的租户 Key 已失效", exact=True)))
        check("页头标识变成失效", seen(page.locator("header .who.bad")))
        check("失效引导说明管理登录仍有效，401 只出现在最后一行小字",
              "管理登录仍然有效" in body() and "401" not in page.locator(".state h2").inner_text()
              and "401" not in page.locator(".state p").first.inner_text())
        shot("assets-key-rejected")
        go("/tenants")
        check("Key 失效时管理页仍可用", seen(page.locator("tr", has_text=tname)))
        go("/key")
        page.fill("#k", key2)
        page.fill("#l", "rotated")
        page.get_by_role("button", name="使用这把 Key", exact=True).click()
        go("/assets")
        check("粘贴新 Key 后回到资产列表", seen(page.get_by_role("heading", name="资产列表", exact=True)))
        go(f"/tenants/{tenant_id}")
        page.locator("tr", has_text="console-live").first.get_by_role("button", name="撤销", exact=True).click()
        page.wait_for_load_state("networkidle")
        check("撤销 Key 有确认框", any("撤销" in m for m in dialogs))
        go("/usage")
        check("撤销后租户页显示失效引导", seen(page.get_by_role("heading", name="当前选用的租户 Key 已失效", exact=True)))

        page.evaluate("() => sessionStorage.setItem('vod.admin.token', 'vadm1.forged.token')")
        page.reload()
        go("/tenants")
        page.wait_for_url(re.compile(r"#/login"))
        check("管理 JWT 被拒绝后回登录页并说明已过期", seen(page.get_by_text("管理登录已过期")))
        check("租户 Key 保留在本标签页", page.evaluate("() => sessionStorage.getItem('vod.key')") == key2)
        page.fill("#u", user)
        page.fill("#p", password)
        page.get_by_role("button", name="登录", exact=True).click()
        page.wait_for_url(re.compile(r"#/tenants"))
        check("重新登录后回到原页面", page.url.endswith("#/tenants"))
        check("服务端直接拒绝被撤销的 Key", raw_status("/api/v1/assets", {"X-Api-Key": key2}) == 401)
        check("服务端直接拒绝伪造的管理 JWT", raw_status("/api/v1/admin/tenants", {"Authorization": "Bearer vadm1.forged"}) == 401)

        # ---------- 10. 窄屏与退出 ----------
        print("== 10. 窄屏抽屉与退出", flush=True)
        page.set_viewport_size({"width": 375, "height": 812})
        page.wait_for_timeout(300)
        no_overflow = page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 1")
        check("375 宽无横向溢出", no_overflow)
        page.get_by_role("button", name="打开导航", exact=True).click()
        side = page.locator("#side")
        page.wait_for_timeout(300)
        opened = page.evaluate("() => { const r = document.querySelector('#side').getBoundingClientRect(); return r.right > 100 }")
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)
        closed = page.evaluate("() => { const r = document.querySelector('#side').getBoundingClientRect(); return r.right <= 1 }")
        check("375 宽抽屉可打开、Esc 关闭", opened and closed, f"opened={opened} closed={closed}")
        page.set_viewport_size({"width": 1440, "height": 900})
        legacy = page.locator("a", has_text="短剧场").get_attribute("href")
        check("侧栏底部「短剧场」指向旧播放页", legacy == "/")
        page.locator("header .who.admin").get_by_role("button", name="退出", exact=True).click()
        page.wait_for_load_state("networkidle")
        check("退出后页头显示未登录", seen(page.locator("header .who.none")) or "#/login" in page.url)
        check("全程无页面脚本错误", not errors, "; ".join(errors[:3]))
        browser.close()

    passed = sum(1 for _, ok in results if ok)
    print(f"\n== 结果：{passed}/{len(results)} 通过", flush=True)
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
