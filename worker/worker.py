"""FFmpeg 转码 worker：消费 RabbitMQ 的 transcode 队列，产出多码率 HLS 写回对象存储。

一条消息的处理顺序（看懂这个函数就看懂了整条流水线）：
  1. 抢占任务：带条件的 UPDATE（状态机 + 租约），抢不到说明是重复消息 -> 直接 ack
  2. 下载原片 -> ffprobe 校验
  3. 付费集：取/建内容密钥（重试复用同一把 key）
  4. 一条 ffmpeg 命令出 3 档码率，强制关键帧对齐，首片 1s、之后 4s
  5. 上传分片和子 m3u8，最后才上传 master.m3u8（master 出现 = 产物完整）
  6. 一个事务里：任务 SUCCEEDED、资产 READY、集指向新资产；删 Redis 缓存
  失败：attempts < max -> 进延迟重试队列（指数退避）；否则 FAILED + 进死信队列
"""
import json, mimetypes, os, shutil, socket, subprocess, threading, time

from prometheus_client import Counter, Gauge, Histogram, start_http_server

from common import (HLS_BUCKET, Q_DLQ, Q_MAIN, Q_RETRY, db, log, mq_channel, publish, rds, s3,
                    transcode_message, wait_for)
from hls_verify import verify_hls
from job_route import hls_prefix, job_table, updates_episode

WORKER_ID = f"{socket.gethostname()}-{os.getpid()}"
LEASE_MIN = 30
FIRST_SEG, SEG = 1, 4   # 首片 1s（秒开），之后 4s
# 码率阶梯：(名字, 短边像素, 视频码率kbps)。竖屏短剧 720p = 720x1280。另一条边按比例算(-2 保证偶数)
LADDER = [("720p", 720, 2500), ("540p", 540, 1200), ("360p", 360, 600)]

JOBS = Counter("vod_transcode_jobs_total", "transcode job results", ["result"])
DUR = Histogram("vod_transcode_seconds", "transcode wall time", buckets=(5, 10, 30, 60, 120, 300, 600, 1800))
INFLIGHT = Gauge("vod_transcode_inflight", "jobs running in this worker")

s3c = s3()
r = rds()


def ensure_claim_version():
    """旧库没有这一列。新安装由 init.sql 建好，这里只补已有卷。"""
    conn = db()
    with conn.cursor() as c:
        c.execute("SHOW COLUMNS FROM transcode_job LIKE 'claim_version'")
        if not c.fetchone():
            c.execute("ALTER TABLE transcode_job ADD COLUMN claim_version INT NOT NULL DEFAULT 0")
    conn.close()


def claim(c, job_id, table="transcode_job", steal=False):
    """PENDING/RETRYING 可抢。RUNNING 只在租约过期，或消息被重新投递（原进程已死）时抢走。claim_version +1，旧进程不能再提交。"""
    table = job_table("core" if table == "vod_transcode_job" else "legacy") if table in ("transcode_job", "vod_transcode_job") else job_table(table)
    running = "status='RUNNING'" if steal else "(status='RUNNING' AND locked_until < NOW())"
    n = c.execute(
        f"UPDATE {table} SET status='RUNNING', attempts=attempts+1, worker_id=%s, "
        "claim_version=claim_version+1, locked_until=NOW() + INTERVAL %s MINUTE "
        f"WHERE id=%s AND (status IN ('PENDING','RETRYING') OR {running})",
        (WORKER_ID, LEASE_MIN, job_id))
    return n == 1


def renew_lease(job_id, version, table="transcode_job"):
    table = job_table("core" if table == "vod_transcode_job" else "legacy")
    conn = db()
    try:
        with conn.cursor() as c:
            return c.execute(
                f"UPDATE {table} SET locked_until=NOW() + INTERVAL %s MINUTE "
                "WHERE id=%s AND claim_version=%s AND status='RUNNING'",
                (LEASE_MIN, job_id, version)) == 1
    finally:
        conn.close()


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"{cmd[0]} failed: {p.stderr[-800:]}")
    return p.stdout


def probe(path):
    info = json.loads(run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path]))
    v = next((s for s in info["streams"] if s["codec_type"] == "video"), None)
    if not v:
        raise RuntimeError("no video stream")
    has_audio = any(s["codec_type"] == "audio" for s in info["streams"])
    return int(v["width"]), int(v["height"]), int(float(info["format"]["duration"]) * 1000), has_audio


def build_ffmpeg(src, out, ladder, has_audio, keyinfo, portrait):
    n = len(ladder)
    split = f"[0:v]split={n}" + "".join(f"[s{i}]" for i in range(n))
    scales = ";".join(f"[s{i}]scale={f'{p}:-2' if portrait else f'-2:{p}'}[v{i}]"
                      for i, (_, p, _) in enumerate(ladder))
    cmd = ["ffmpeg", "-y", "-v", "error", "-i", src, "-filter_complex", f"{split};{scales}"]
    for i in range(n):
        cmd += ["-map", f"[v{i}]"]
        if has_audio:
            cmd += ["-map", "0:a:0"]
    cmd += ["-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-pix_fmt", "yuv420p",
            # 关键帧对齐：关掉场景切换插 I 帧，GOP 上限放大，靠 force_key_frames 按"时间"打点，
            # 所以各档的关键帧时间点完全一致 -> 分片边界一致 -> 播放器能在任意分片边界无缝切档
            "-sc_threshold", "0", "-g", "600",
            "-force_key_frames", f"expr:gte(t,if(eq(n_forced,0),0,{FIRST_SEG}+(n_forced-1)*{SEG}))"]
    for i, (_, _, kbps) in enumerate(ladder):
        cmd += [f"-b:v:{i}", f"{kbps}k", f"-maxrate:v:{i}", f"{int(kbps * 1.1)}k", f"-bufsize:v:{i}", f"{kbps * 2}k"]
    if has_audio:
        cmd += ["-c:a", "aac", "-b:a", "96k", "-ac", "2", "-ar", "48000"]
    vsm = " ".join(f"v:{i},a:{i},name:{name}" if has_audio else f"v:{i},name:{name}"
                   for i, (name, _, _) in enumerate(ladder))
    # 注意：hls_init_time 在 VOD(列表不滚动)下对后续分片也生效，达不到"只有首片短"的效果，
    # 所以这里 hls_time 设得很小，让"每个强制关键帧都切一刀"，分片长度完全由关键帧时间点决定。
    cmd += ["-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", "-hls_flags", "independent_segments",
            "-hls_segment_filename", f"{out}/%v/seg_%05d.ts", "-master_pl_name", "master.m3u8",
            "-var_stream_map", vsm]
    if keyinfo:
        cmd += ["-hls_key_info_file", keyinfo]
    return cmd + [f"{out}/%v/index.m3u8"]


def get_or_create_key(c, asset_id):
    c.execute("INSERT IGNORE INTO content_key(asset_id, key_hex, iv_hex) VALUES(%s,%s,%s)",
              (asset_id, os.urandom(16).hex(), os.urandom(16).hex()))
    c.execute("SELECT key_hex, iv_hex FROM content_key WHERE asset_id=%s", (asset_id,))
    return c.fetchone()


def upload_dir(local, prefix):
    files = sorted(os.path.relpath(os.path.join(d, f), local) for d, _, fs in os.walk(local) for f in fs)
    files.sort(key=lambda f: f == "master.m3u8")  # master 最后传
    for rel in files:
        ctype = "application/vnd.apple.mpegurl" if rel.endswith(".m3u8") else (
            mimetypes.guess_type(rel)[0] or "video/mp2t")
        s3c.upload_file(os.path.join(local, rel), HLS_BUCKET, prefix + rel, ExtraArgs={"ContentType": ctype})
    return len(files)


def process(job_id, trace_id, origin="legacy", steal=False):
    table = job_table(origin)
    conn = db()
    c = conn.cursor()
    if not claim(c, job_id, table, steal=steal):
        c.execute(f"SELECT status, locked_until FROM {table} WHERE id=%s", (job_id,))
        row = c.fetchone()
        conn.close()
        log("skip_duplicate", job_id=job_id, status=row and row["status"], trace_id=trace_id, origin=origin)
        # 别人正在跑（租约未到期）：延迟再看；已终态：丢弃
        return "requeue" if row and row["status"] == "RUNNING" else "drop"
    if origin == "core":
        c.execute("SELECT j.attempts, j.max_attempts, j.claim_version, a.id, a.source_bucket, a.source_key, "
                  "a.encrypted FROM vod_transcode_job j JOIN vod_media_asset a ON a.id=j.asset_id WHERE j.id=%s",
                  (job_id,))
    else:
        c.execute("SELECT j.attempts, j.max_attempts, j.claim_version, a.* FROM transcode_job j "
                  "JOIN video_asset a ON a.id=j.asset_id WHERE j.id=%s", (job_id,))
    a = c.fetchone()
    if origin == "core":
        a["episode_id"] = None
    asset_id = a["id"]
    version = a["claim_version"]
    fenced = threading.Event()

    def beat():
        while not fenced.wait(60):
            if not renew_lease(job_id, version, table):
                fenced.set()
                log("lease_lost", job_id=job_id, claim_version=version, trace_id=trace_id)
    threading.Thread(target=beat, daemon=True).start()
    if origin == "core":
        c.execute("UPDATE vod_media_asset SET status='TRANSCODING' WHERE id=%s", (asset_id,))
    else:
        c.execute("UPDATE video_asset SET status='TRANSCODING' WHERE id=%s", (asset_id,))
    work = f"/tmp/job_{job_id}"
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f"{work}/out")
    t0 = time.time()
    INFLIGHT.inc()
    log("job_start", job_id=job_id, asset_id=asset_id, attempt=a["attempts"], trace_id=trace_id, worker=WORKER_ID)
    try:
        src = f"{work}/source"
        s3c.download_file(a["source_bucket"], a["source_key"], src)
        w, h, dur_ms, has_audio = probe(src)
        portrait = h >= w
        short = min(w, h)
        ladder = [x for x in LADDER if x[1] <= short]
        if not ladder:  # 小于 360 也不放大，按源的短边出一档
            even = max(2, short // 2 * 2)
            ladder = [(f"{even}p", even, 600)]
        keyinfo = None
        if a["encrypted"]:
            k = get_or_create_key(c, asset_id)
            with open(f"{work}/enc.key", "wb") as f:
                f.write(bytes.fromhex(k["key_hex"]))
            keyinfo = f"{work}/keyinfo"
            with open(keyinfo, "w") as f:  # 三行：写进 m3u8 的 key URI / 本地 key 文件 / IV
                f.write(f"/keys/{asset_id}\n{work}/enc.key\n{k['iv_hex']}\n")
        run(build_ffmpeg(src, f"{work}/out", ladder, has_audio, keyinfo, portrait))
        verify_hls(f"{work}/out", [name for name, _, _ in ladder], bool(a["encrypted"]))
        if fenced.is_set():
            log("drop_fenced", job_id=job_id, claim_version=version, trace_id=trace_id)
            return "drop"
        prefix = hls_prefix(origin, asset_id)
        nfiles = upload_dir(f"{work}/out", prefix)
        conn.begin()
        if updates_episode(origin):
            for name, p, kbps in ladder:
                other = round(max(w, h) * p / min(w, h) / 2) * 2
                rw, rh = (p, other) if portrait else (other, p)
                c.execute("INSERT INTO asset_rendition(asset_id, name, width, height, video_kbps, playlist_key) "
                          "VALUES(%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE video_kbps=VALUES(video_kbps)",
                          (asset_id, name, rw, rh, kbps, f"{prefix}{name}/index.m3u8"))
            c.execute("UPDATE video_asset SET status='READY', hls_prefix=%s, duration_ms=%s, width=%s, height=%s "
                      "WHERE id=%s", (prefix, dur_ms, w, h, asset_id))
            c.execute("UPDATE episode SET asset_id=%s, status='READY' WHERE id=%s", (asset_id, a["episode_id"]))
        else:
            c.execute("UPDATE vod_media_asset SET status='READY', hls_prefix=%s, duration_ms=%s, width=%s, height=%s "
                      "WHERE id=%s", (prefix, dur_ms, w, h, asset_id))
        changed = c.execute(
            f"UPDATE {table} SET status='SUCCEEDED', locked_until=NULL, last_error=NULL "
            "WHERE id=%s AND claim_version=%s", (job_id, version))
        if changed != 1:
            conn.rollback()
            log("drop_fenced", job_id=job_id, claim_version=version, trace_id=trace_id)
            return "drop"
        conn.commit()
        if a.get("episode_id"):
            r.delete(f"ep:{a['episode_id']}")  # 缓存失效：下次播放读到新 asset_id
        JOBS.labels("succeeded").inc()
        DUR.observe(time.time() - t0)
        log("job_done", job_id=job_id, asset_id=asset_id, renditions=[x[0] for x in ladder], files=nfiles,
            secs=round(time.time() - t0, 1), trace_id=trace_id)
        return "ok"
    except Exception as e:
        conn.rollback()
        final = a["attempts"] >= a["max_attempts"]
        c.execute(f"UPDATE {table} SET status=%s, locked_until=NULL, last_error=%s "
                  "WHERE id=%s AND claim_version=%s",
                  ("FAILED" if final else "RETRYING", str(e)[-2000:], job_id, version))
        if final and origin == "core":
            c.execute("UPDATE vod_media_asset SET status='FAILED' WHERE id=%s", (asset_id,))
        elif final:
            c.execute("UPDATE video_asset SET status='FAILED' WHERE id=%s", (asset_id,))
        JOBS.labels("failed" if final else "retry").inc()
        log("job_error", job_id=job_id, attempt=a["attempts"], final=final, err=str(e)[-300:], trace_id=trace_id)
        return "dlq" if final else ("retry", 10_000 * 2 ** (a["attempts"] - 1))
    finally:
        fenced.set()
        INFLIGHT.dec()
        conn.close()
        shutil.rmtree(work, ignore_errors=True)


def finish(ch, method, msg, res):
    if res == "requeue":
        publish(ch, Q_RETRY, msg, delay_ms=60_000)
    elif res == "dlq":
        publish(ch, Q_DLQ, msg)
    elif isinstance(res, tuple):
        publish(ch, Q_RETRY, msg, delay_ms=res[1])
    ch.basic_ack(method.delivery_tag)  # 处理完（含转投重试/死信）才 ack；中途崩溃消息会被重投


def on_message(ch, method, props, body):
    """转码可能跑几分钟，放到子线程里做；主线程继续跑 pika 的 IO 循环发心跳，
    否则 broker 会以为连接死了把它断掉。pika 的 channel 不是线程安全的，所以 ack 要切回主线程。"""
    msg = json.loads(body)  # v1 只读 job_id / trace_id；未知字段与 schema_version 忽略，状态以数据库为准
    conn = ch.connection

    def work():
        try:
            res = process(msg["job_id"], msg.get("trace_id"), msg.get("origin") or "legacy",
                          steal=bool(method.redelivered))
        except Exception as e:  # 比如数据库连不上：稍后重试
            log("process_crash", job_id=msg.get("job_id"), err=str(e))
            res = "requeue"
        conn.add_callback_threadsafe(lambda: finish(ch, method, msg, res))

    threading.Thread(target=work, daemon=True).start()


def sweeper():
    """补偿扫描（发件箱的简化版）：API 落库成功但发 MQ 失败、或 worker 崩溃租约过期的任务，重新投递。"""
    while True:
        try:
            conn = db()
            with conn.cursor() as c:
                c.execute("SELECT id, asset_id, trace_id FROM transcode_job WHERE "
                          "(status='PENDING' AND updated_at < NOW() - INTERVAL 2 MINUTE) OR "
                          "(status='RUNNING' AND locked_until < NOW())")
                rows = c.fetchall()
            conn.close()
            core_rows = []
            try:
                c2 = db()
                with c2.cursor() as cur:
                    cur.execute("SELECT id, asset_id FROM vod_transcode_job WHERE "
                                "(status='PENDING' AND updated_at < NOW() - INTERVAL 2 MINUTE) OR "
                                "(status='RUNNING' AND locked_until < NOW())")
                    core_rows = cur.fetchall()
                c2.close()
            except Exception as e:
                log("sweeper_core_skip", err=str(e)[:120])
            if rows or core_rows:
                mconn, mch = mq_channel()
                for row in rows:
                    publish(mch, Q_MAIN, transcode_message(row["id"], row["asset_id"], row["trace_id"]))
                    log("sweeper_republish", job_id=row["id"])
                for row in core_rows:
                    body = transcode_message(row["id"], row["asset_id"], None)
                    body["origin"] = "core"
                    publish(mch, Q_MAIN, body)
                    log("sweeper_republish_core", job_id=row["id"])
                mconn.close()
        except Exception as e:
            log("sweeper_error", err=str(e))
        time.sleep(60)


def main():
    start_http_server(9100)
    wait_for("mysql", lambda: db().close())
    ensure_claim_version()
    mconn, ch = wait_for("rabbitmq", mq_channel)
    threading.Thread(target=sweeper, daemon=True).start()
    ch.basic_qos(prefetch_count=1)  # 一个 worker 同时只拿一条，转码是 CPU 密集，靠多开 worker 横向扩
    ch.basic_consume(Q_MAIN, on_message)
    log("worker_started", worker=WORKER_ID)
    ch.start_consuming()


if __name__ == "__main__":
    main()
