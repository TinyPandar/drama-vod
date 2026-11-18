"""可选：用无头 Chrome 打开测试页，验证 hls.js 在真实浏览器里能播放（需要本机 Chrome/Chromium + websocket-client）。

浏览器默认 google-chrome；别的路径用环境变量 CHROME_BIN 指定（如 Playwright 下载的 Chrome for Testing）。
"""
import json, os, subprocess, sys, time, urllib.request, websocket
URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/?ep=1&uid=7"
p = subprocess.Popen([os.environ.get("CHROME_BIN", "google-chrome"), "--headless=new", "--no-sandbox", "--disable-gpu",
    "--autoplay-policy=no-user-gesture-required", "--remote-debugging-port=9333",
    "--remote-allow-origins=http://127.0.0.1:9333",
    "--user-data-dir=/tmp/cdp", "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(50):
        try: tabs = json.load(urllib.request.urlopen("http://127.0.0.1:9333/json")); break
        except Exception: time.sleep(0.2)
    ws = websocket.create_connection([t for t in tabs if t["type"] == "page"][0]["webSocketDebuggerUrl"])
    n = [0]
    def call(m, **params):
        n[0] += 1; ws.send(json.dumps({"id": n[0], "method": m, "params": params}))
        while True:
            r = json.loads(ws.recv())
            if r.get("id") == n[0]: return r.get("result", {})
    call("Page.navigate", url=URL); time.sleep(12)
    js = "JSON.stringify({state: window.vodState, t: document.getElementById('v').currentTime, log: document.getElementById('log').textContent})"
    print(json.loads(call("Runtime.evaluate", expression=js)["result"]["value"]) and call("Runtime.evaluate", expression=js)["result"]["value"])
finally:
    p.kill()
