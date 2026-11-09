#!/usr/bin/env python3
"""Phase 3 故障注入：docker compose + Core /api/v1 链路。每个场景打印预期结局和 PASS/FAIL。

  python3 scripts/fault_inject.py                       # 跑全部场景
  python3 scripts/fault_inject.py --only cancel,dup     # 只跑部分：kill,dup,minio,rabbitmq,cancel

前提：在仓库根目录、`docker compose up -d --build` 已经起好；本机有 ffmpeg（生成测试视频）和 PyMySQL。
脚本会用缩短的租约/心跳（--lease-seconds/--heartbeat-seconds）重建 worker 并 --scale worker=2，
结束时按默认配置恢复成 1 个 worker（--no-restore 跳过）。数据库口令从环境变量 MYSQL_PASSWORD 或 .env 读，不打印。
超时都是脚本参数；只报告真实跑出的结果，不做性能结论。
"""
import argparse, json, os, pathlib, subprocess, sys, tempfile, time, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]


def dotenv(name):
    if os.environ.get(name):
        return os.environ[name]
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith(name + "="):
                return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


BASE = os.environ.get("VOD_BASE") or f"http://localhost:{dotenv('HTTP_PORT') or 8080}"
KEY = os.environ.get("VOD_API_KEY") or dotenv("VOD_DEV_API_KEY") or "dev-demo-key"


# ---------- 基础设施 ----------

def sh(args, env=None, check=True, capture=True, stdin=None):
    p = subprocess.run(args, cwd=ROOT, env={**os.environ, **(env or {})}, text=True, input=stdin,
                       capture_output=capture)
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} -> {p.returncode}: {(p.stderr or '')[-400:]}")
    return p.stdout if capture else ""


def compose(*args, env=None, check=True):
    return sh(["docker", "compose", *args], env=env, check=check)


def http(method, path, body=None, data=None, headers=None, key=KEY, timeout=60):
    url = path if path.startswith("http") else BASE + path
    headers = dict(headers or {})
    if key:
        headers["X-Api-Key"] = key
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw[:200].decode("utf-8", "replace")}


def db():
    import pymysql
    return pymysql.connect(host=os.environ.get("MYSQL_HOST", "127.0.0.1"), port=int(os.environ.get("MYSQL_PORT", "3306")),
                           user=os.environ.get("MYSQL_USER", "vod"), password=dotenv("MYSQL_PASSWORD"),
                           database=os.environ.get("MYSQL_DB", "vod"), autocommit=True,
                           cursorclass=pymysql.cursors.DictCursor)


def one(sql, *args):
    conn = db()
    try:
        with conn.cursor() as c:
            c.execute(sql, args)
            return c.fetchone()
    finally:
        conn.close()


def job(job_id):
    return one("SELECT id, status, attempts, claim_version, worker_id, error_code, last_error "
               "FROM vod_transcode_job WHERE id=%s", job_id)


def asset_status(asset_id):
    row = one("SELECT status FROM vod_media_asset WHERE id=%s", asset_id)
    return row and row["status"]


def wait(fn, timeout, interval=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(interval)
    return None


def now_utc():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def worker_events(since):
    """docker compose logs 里的 worker JSON 事件（所有副本）。"""
    out = compose("logs", "--no-color", "--since", since, "worker", check=False)
    events = []
    for line in out.splitlines():
        start = line.find("{")
        if start < 0:
            continue
        try:
            events.append(json.loads(line[start:]))
        except ValueError:
            pass
    return events


def worker_env(args, default=False):
    if default:
        return {k: "" for k in ("WORKER_LEASE_SECONDS", "WORKER_HEARTBEAT_SECONDS", "WORKER_REQUEUE_DELAY_MS",
                                "WORKER_RETRY_BASE_MS", "WORKER_RETRY_MAX_MS", "WORKER_SWEEP_INTERVAL_SECONDS",
                                "WORKER_SWEEP_PENDING_AGE_SECONDS")}
    return {"WORKER_LEASE_SECONDS": str(args.lease_seconds), "WORKER_HEARTBEAT_SECONDS": str(args.heartbeat_seconds),
            "WORKER_REQUEUE_DELAY_MS": str(args.requeue_ms), "WORKER_RETRY_BASE_MS": str(args.retry_base_ms),
            "WORKER_RETRY_MAX_MS": str(args.retry_base_ms * 8), "WORKER_SWEEP_INTERVAL_SECONDS": "10",
            "WORKER_SWEEP_PENDING_AGE_SECONDS": "20"}


def scale_workers(n, env):
    compose("up", "-d", "--no-deps", "--force-recreate", "--scale", f"worker={n}", "worker", env=env)
    ids = compose("ps", "-q", "worker").split()
    if len(ids) != n:
        raise RuntimeError(f"expected {n} worker containers, got {len(ids)}")
    time.sleep(3)  # 等 worker 连上 MySQL / RabbitMQ
    return ids


def container_for(worker_id):
    """WORKER_ID = 主机名-pid；容器主机名就是容器 id 前缀。"""
    host = worker_id.rsplit("-", 1)[0]
    for cid in compose("ps", "-q", "worker").split():
        if sh(["docker", "inspect", "-f", "{{.Config.Hostname}}", cid]).strip() == host:
            return cid
    return None


def mp4(path, seconds):
    sh(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", str(seconds),
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", path])


class Media:
    def __init__(self, workdir):
        self.dir, self.cache = workdir, {}

    def file(self, seconds):
        if seconds not in self.cache:
            path = os.path.join(self.dir, f"src_{seconds}s.mp4")
            mp4(path, seconds)
            self.cache[seconds] = path
        return self.cache[seconds]


def upload(media, seconds):
    status, opened = http("POST", "/api/v1/uploads", {"encrypted": False, "filename": "fault.mp4"})
    assert status == 200, (status, opened)
    with open(media.file(seconds), "rb") as f:
        req = urllib.request.Request(opened["put_url"], data=f.read(), method="PUT",
                                     headers={"Content-Type": "video/mp4"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        assert resp.status in (200, 204), resp.status
    return opened


def complete(opened):
    status, done = http("POST", f"/api/v1/uploads/{opened['session_id']}/complete")
    assert status == 200, (status, done)
    return done["job_id"]


def submit(media, seconds):
    opened = upload(media, seconds)
    return opened["asset_id"], complete(opened)


def wait_terminal(asset_id, timeout):
    return wait(lambda: (asset_status(asset_id) in ("READY", "FAILED", "CANCELLED")) and asset_status(asset_id),
                timeout, 2)


def publish_duplicates(job_ids_assets, copies):
    """在 worker 容器里用同一份 common.py 往主队列重复投递 v1 消息（附 origin=core）。"""
    script = (
        "import json, sys\n"
        "sys.path.insert(0, '/app')\n"
        "from common import Q_MAIN, mq_channel, publish, transcode_message\n"
        f"pairs = json.loads({json.dumps(json.dumps(job_ids_assets))})\n"
        "conn, ch = mq_channel()\n"
        f"for _ in range({copies}):\n"
        "    for job_id, asset_id in pairs:\n"
        "        body = transcode_message(job_id, asset_id, 'fault-dup')\n"
        "        body['origin'] = 'core'\n"
        "        publish(ch, Q_MAIN, body)\n"
        "conn.close()\n")
    sh(["docker", "compose", "exec", "-T", "worker", "python", "-"], stdin=script)


# ---------- 场景 ----------

def scenario_kill(args, media):
    """kill 正在转码的 worker → 租约过期或重投后由另一个 worker 完成，资产 READY，claim_version ≥ 2。"""
    asset_id, job_id = submit(media, args.video_seconds)
    running = wait(lambda: (lambda j: j if j and j["status"] == "RUNNING" and j["worker_id"] else None)(job(job_id)),
                   args.timeout)
    if not running:
        return False, f"job {job_id} never RUNNING: {job(job_id)}"
    cid = container_for(running["worker_id"])
    if not cid:
        return False, f"container for {running['worker_id']} not found"
    sh(["docker", "kill", cid])
    try:
        final = wait_terminal(asset_id, args.lease_seconds + args.timeout)
    finally:
        sh(["docker", "start", cid], check=False)
    j = job(job_id)
    ok = final == "READY" and j["status"] == "SUCCEEDED" and j["claim_version"] >= 2
    return ok, f"asset {asset_id}={final}, job {job_id} status={j['status']} claim_version={j['claim_version']} " \
               f"killed={running['worker_id']}"


def scenario_dup(args, media):
    """两个 worker + 每个任务额外重复投递 → 每个 (job, claim_version) 只有一个进程 job_start；
    每个任务 job_done 恰好一次；全部 READY。"""
    since = now_utc()
    pairs = []
    for _ in range(args.jobs):
        asset_id, job_id = submit(media, args.short_seconds)
        pairs.append((job_id, asset_id))
    publish_duplicates(pairs, args.copies)
    finals = {a: wait_terminal(a, args.timeout) for _, a in pairs}
    time.sleep(2)
    events = [e for e in worker_events(since) if e.get("job_id") in {j for j, _ in pairs}]
    starts, done = {}, {}
    for e in events:
        if e.get("event") == "job_start":
            starts.setdefault((e["job_id"], e.get("claim_version")), set()).add(e.get("worker"))
        elif e.get("event") == "job_done":
            done[e["job_id"]] = done.get(e["job_id"], 0) + 1
    clashes = {k: v for k, v in starts.items() if len(v) != 1}
    ok = (all(s == "READY" for s in finals.values()) and not clashes
          and all(done.get(j) == 1 for j, _ in pairs) and all(k[1] is not None for k in starts))
    return ok, f"finals={finals} job_done={done} starts={{{', '.join(f'{k}: {sorted(v)}' for k, v in starts.items())}}} " \
               f"clashes={clashes}"


def scenario_minio(args, media):
    """转码中途停 MinIO --outage-seconds 秒再启动 → 重试后 READY，或次数用完 FAILED 且带 error_code。
    要求故障确实打到了任务（日志里出现该任务的 job_error），否则判 FAIL（加大 --video-seconds）。"""
    since = now_utc()
    asset_id, job_id = submit(media, args.video_seconds)
    if not wait(lambda: (job(job_id) or {}).get("status") == "RUNNING", args.timeout, 0.5):
        return False, f"job {job_id} never RUNNING"
    compose("stop", "minio")
    try:
        time.sleep(args.outage_seconds)
    finally:
        compose("start", "minio")
    final = wait_terminal(asset_id, args.timeout * 2)
    j = job(job_id)
    errors = [e for e in worker_events(since) if e.get("event") == "job_error" and e.get("job_id") == job_id]
    hit = bool(errors)
    ok = hit and (final == "READY" or (final == "FAILED" and j["status"] == "FAILED" and j["error_code"]))
    return ok, f"asset {asset_id}={final}, job status={j['status']} attempts={j['attempts']} " \
               f"error_code={j['error_code']} job_errors={[e.get('error_code') for e in errors]}"


def scenario_rabbitmq(args, media):
    """RabbitMQ 停机期间 complete → outbox 留在 PENDING；重启后 OutboxPublisher 重新发布、worker 重连，资产 READY。"""
    opened = upload(media, args.short_seconds)
    compose("stop", "rabbitmq")
    try:
        job_id = complete(opened)
        row = one("SELECT status FROM vod_outbox WHERE payload LIKE %s", f'%"job_id":{job_id},%')
        pending_while_down = row and row["status"] == "PENDING"
    finally:
        compose("start", "rabbitmq")
    final = wait_terminal(opened["asset_id"], args.timeout * 2)
    row = one("SELECT status, attempts FROM vod_outbox WHERE payload LIKE %s", f'%"job_id":{job_id},%')
    ok = bool(pending_while_down) and final == "READY" and row and row["status"] == "PUBLISHED"
    return ok, f"outbox while down={'PENDING' if pending_while_down else 'not PENDING'}, " \
               f"after={row}, asset {opened['asset_id']}={final}"


def scenario_cancel(args, media):
    """转码中取消 → 200 CANCELLED；worker 心跳发现围栏丢失并丢弃（lease_lost/drop_fenced），
    任务和资产保持 CANCELLED，不出现 READY；再次取消 200；取消 SUCCEEDED 任务 409。"""
    since = now_utc()
    asset_id, job_id = submit(media, args.video_seconds)
    if not wait(lambda: (job(job_id) or {}).get("status") == "RUNNING", args.timeout, 0.5):
        return False, f"job {job_id} never RUNNING"
    status, body = http("POST", f"/api/v1/jobs/{job_id}/cancel")
    if status != 200 or body.get("status") != "CANCELLED":
        return False, f"cancel -> {status} {body}"
    seen = set()
    deadline = time.time() + args.heartbeat_seconds * 3 + args.observe_seconds
    while time.time() < deadline:
        seen.add(asset_status(asset_id))
        time.sleep(1)
    again, body2 = http("POST", f"/api/v1/jobs/{job_id}/cancel")
    fenced = [e["event"] for e in worker_events(since)
              if e.get("job_id") == job_id and e.get("event") in ("lease_lost", "drop_fenced")]
    done = one("SELECT s.id FROM vod_transcode_job s JOIN vod_transcode_job c ON c.tenant_id=s.tenant_id "
               "WHERE c.id=%s AND s.status='SUCCEEDED' ORDER BY s.id DESC LIMIT 1", job_id)
    conflict = http("POST", f"/api/v1/jobs/{done['id']}/cancel")[0] if done else None
    j = job(job_id)
    ok = ("READY" not in seen and j["status"] == "CANCELLED" and asset_status(asset_id) == "CANCELLED"
          and again == 200 and body2.get("status") == "CANCELLED" and fenced and conflict in (409, None))
    return ok, f"asset states seen={sorted(s for s in seen if s)}, job={j['status']} v{j['claim_version']}, " \
               f"worker events={fenced}, cancel again={again}, cancel SUCCEEDED job={conflict}"


SCENARIOS = {"kill": scenario_kill, "dup": scenario_dup, "minio": scenario_minio,
             "rabbitmq": scenario_rabbitmq, "cancel": scenario_cancel}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", default=",".join(SCENARIOS), help="comma list: " + ",".join(SCENARIOS))
    ap.add_argument("--timeout", type=int, default=180, help="per-wait timeout seconds")
    ap.add_argument("--lease-seconds", type=int, default=20)
    ap.add_argument("--heartbeat-seconds", type=int, default=5)
    ap.add_argument("--requeue-ms", type=int, default=3000)
    ap.add_argument("--retry-base-ms", type=int, default=3000)
    ap.add_argument("--video-seconds", type=int, default=60, help="long source so faults land mid-transcode")
    ap.add_argument("--short-seconds", type=int, default=6)
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--copies", type=int, default=3, help="extra deliveries per job in the dup scenario")
    ap.add_argument("--outage-seconds", type=int, default=15)
    ap.add_argument("--observe-seconds", type=int, default=20, help="extra time to watch a cancelled job")
    ap.add_argument("--no-restore", action="store_true")
    args = ap.parse_args()
    chosen = [s.strip() for s in args.only.split(",") if s.strip()]
    unknown = [s for s in chosen if s not in SCENARIOS]
    if unknown:
        ap.error(f"unknown scenario(s): {unknown}")

    status, _ = http("GET", "/api/v1/healthz", key=None)
    if status != 200:
        raise SystemExit(f"vod-api not reachable at {BASE} ({status}); run docker compose up -d --build first")
    print(f"worker x2, lease={args.lease_seconds}s heartbeat={args.heartbeat_seconds}s", flush=True)
    scale_workers(2, worker_env(args))
    results = []
    try:
        with tempfile.TemporaryDirectory() as tmp:
            media = Media(tmp)
            for name in chosen:
                fn = SCENARIOS[name]
                print(f"\n== [{name}] 预期：{' '.join(fn.__doc__.split())}", flush=True)
                try:
                    ok, detail = fn(args, media)
                except Exception as e:  # 场景本身出错也算 FAIL，继续下一个
                    ok, detail = False, f"error: {e!r}"
                results.append((name, ok))
                print(f"{'PASS' if ok else 'FAIL'} {name}: {detail}", flush=True)
    finally:
        if not args.no_restore:
            scale_workers(1, worker_env(args, default=True))
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} scenarios passed" + (f"; failed: {failed}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
