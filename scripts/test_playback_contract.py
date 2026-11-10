#!/usr/bin/env python3
"""不需要 Docker。Phase 4 播放签名与 Nginx 配置的静态契约：
- docs/contracts/hls-sign-vectors.json 的每条向量，用 Nginx 模板里对应 location 的 secure_link_md5 表达式代入变量后算出同一签名
  （Java 侧 PlaybackSignVectorsTest 读同一份文件），Java 与 Nginx 共用一套测试向量；
- 带 gen 的 /hls/v1/ location 在不带 gen 的那条之前，缓存键含 gen；
- 旧 Demo 的 /hls/ location（签名、缓存键、回源）与 /keys/ 原样未动。"""
import base64, hashlib, json, os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
TEMPLATE = open(os.path.join(ROOT, "nginx", "templates", "default.conf.template"), encoding="utf-8").read()
VECTORS = json.load(open(os.path.join(ROOT, "docs", "contracts", "hls-sign-vectors.json"), encoding="utf-8"))
errors = []


def check(name, ok):
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        errors.append(name)


def locations():
    """[(regex, body)]，按出现顺序。"""
    out = []
    for m in re.finditer(r"location ~ (\S+) \{", TEMPLATE):
        depth, i = 1, m.end()
        while depth:
            depth += {"{": 1, "}": -1}.get(TEMPLATE[i], 0)
            i += 1
        out.append((m.group(1), TEMPLATE[m.end():i - 1]))
    return out


def py(regex):
    """PCRE 命名组 (?<x>...) 转成 Python 的 (?P<x>...)。"""
    return regex.replace("(?<", "(?P<")


def nginx_sig(expr, values):
    raw = expr
    for name, value in values.items():
        raw = raw.replace("${" + name + "}", str(value)).replace("$" + name, str(value))
    return base64.urlsafe_b64encode(hashlib.md5(raw.encode()).digest()).decode().rstrip("=")


locs = locations()
by_kind = {}
for regex, body in locs:
    md5 = re.search(r'secure_link_md5 "([^"]+)";', body)
    if not md5:
        continue
    if regex.startswith("^/hls/v1/") and "(?<gen>" in regex:
        by_kind["v1gen"] = (regex, body, md5.group(1))
    elif regex.startswith("^/hls/v1/"):
        by_kind["v1"] = (regex, body, md5.group(1))
    elif regex.startswith("^/hls/"):
        by_kind["legacy"] = (regex, body, md5.group(1))
check("template has legacy, v1 and v1gen hls locations", set(by_kind) == {"legacy", "v1", "v1gen"})

for v in VECTORS["vectors"]:
    regex, _, expr = by_kind[v["kind"]]
    values = {"sl_exp": v["exp"], "asset": v["asset_id"], "gen": v["gen"] or "", "HLS_SECRET": VECTORS["secret"]}
    check(f"nginx {v['kind']} md5 matches vector {v['signature']}", nginx_sig(expr, values) == v["signature"])
    path = v["master_url"]
    m = re.match(py(regex), path)
    check(f"{v['kind']} master_url matched by its own location", bool(m) and m.group("sl_sig") == v["signature"])
    first = next((r for r, _ in locs if re.match(py(r), path)), None)
    check(f"{v['kind']} master_url hits the right location first", first == regex)

order = [r for r, _ in locs]
check("gen location precedes fixed-prefix v1 location",
      order.index(by_kind["v1gen"][0]) < order.index(by_kind["v1"][0]))
check("gen cache key includes gen", 'proxy_cache_key "v1/$asset/$gen/$file";' in by_kind["v1gen"][1])
check("gen rewrite keeps gen in origin path", "rewrite ^ /internal/origin/v1/$asset/$gen/$file break;" in by_kind["v1gen"][1])
check("gen regex only digits-digits", "(?<gen>[0-9]+-[0-9]+)" in by_kind["v1gen"][0])

FROZEN_LEGACY = '''        secure_link $sl_sig,$sl_exp;
        secure_link_md5 "$sl_exp/$asset ${HLS_SECRET}";
        if ($secure_link = "")  { return 403; }   # 签名不对
        if ($secure_link = "0") { return 410; }   # 已过期

        proxy_cache hls;
        proxy_cache_key "$asset/$file";            # 缓存键不含签名 -> 不同用户命中同一份缓存
        proxy_cache_valid 200 7d;                  # VOD 产物不可变，可以长缓存
        proxy_cache_lock on;                       # 同一对象并发 miss 只回源一次（防击穿）
        proxy_cache_use_stale error timeout updating;
        proxy_ignore_headers Set-Cookie Cache-Control Expires;
        proxy_hide_header Set-Cookie;
        proxy_set_header X-Origin-Secret ${ORIGIN_SECRET};
        rewrite ^ /internal/origin/legacy/$asset/$file break;
        proxy_pass http://api_backend;
        add_header X-Cache-Status $upstream_cache_status always;
'''
check("legacy /hls/ location frozen byte-for-byte", by_kind["legacy"][1].strip() == FROZEN_LEGACY.strip())
FROZEN_KEYS = '''    location /keys/ {
        proxy_pass http://api_backend;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header X-Request-ID $request_id;
        proxy_no_cache 1;
        proxy_cache_bypass 1;
    }'''
check("legacy /keys/ location frozen", FROZEN_KEYS in TEMPLATE)
keys_v1 = TEMPLATE.split("location /keys/v1/ {", 1)[1].split("\n    }\n", 1)[0]
check("/keys/v1/ goes to vod-api, not cached, rate-limited, no-store",
      all(x in keys_v1 for x in ("set $vod_upstream vod-api:8001;", "proxy_no_cache 1;", "limit_req zone=vod_keys",
                                 'add_header Cache-Control "no-store, private" always;')))
check("qoe location rate-limited", re.search(r"location = /api/v1/qoe \{.*?limit_req zone=vod_qoe", TEMPLATE, re.S) is not None)

print(f"\n{len(errors)} failed" if errors else "\nplayback contract ok")
sys.exit(1 if errors else 0)
