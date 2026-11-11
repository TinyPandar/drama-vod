"""FFmpeg 转码 worker：消费 RabbitMQ 的 transcode 队列，产出多码率 HLS 写回对象存储。

一条消息的处理顺序（看懂这个函数就看懂了整条流水线）：
  1. 抢占任务：带条件的 UPDATE（状态机 + 租约），抢不到说明是重复消息 -> 租约内延迟再看，终态直接 ack
  2. 下载原片 -> ffprobe 校验
  3. 付费集：取/建内容密钥（重试复用同一把 key）
  4. 一条 ffmpeg 命令出 3 档码率，强制关键帧对齐，首片 1s、之后 4s
  5. 上传前同步复核围栏；上传分片和子 m3u8，master.m3u8 前再复核一次、最后才上传（master 出现 = 产物完整）
     Core 每次 claim 写到自己的前缀 assets/v1/{asset}/{job}-{claim_version}/（vod_output_prefix 记 WRITING），
     传完再按本地清单 list 一遍远端（名字、大小一致）才提交
  6. 一个事务里：先写带 claim_version 的任务 SUCCEEDED，成功才写资产 READY、集指向新资产；删 Redis 缓存
     Core 的 hls_prefix 和前缀 ACTIVE 在同一事务切换，被替换/失败/取消的前缀由清理线程过宽限期后删除
  资产状态（TRANSCODING/FAILED/READY）只在仍持有当前 claim_version 时更新，旧 worker 不会盖掉新结果
  失败：Core 先分类（retry_policy），不可重试直接 FAILED + 死信；可重试按指数退避 + jitter 进延迟重试队列，
  次数用完 FAILED + 死信；围栏丢了（租约被接手、任务被取消）则杀掉 ffmpeg、什么都不写、直接丢弃
"""
import json, mimetypes, os, shutil, socket, subprocess, threading, time

import pika
from prometheus_client import Counter, Gauge, Histogram, start_http_server

from common import (HLS_BUCKET, Q_DLQ, Q_MAIN, Q_RETRY, db, log, mq_channel, publish, rds, s3,
                    transcode_message, wait_for)
import output_gc, output_prefix, transcode_template
from fencing import apply_failure, claim, commit_success, due_jobs, mark_transcoding, record_failure
from fencing import renew_lease as renew_lease_sql
from hls_verify import local_manifest, verify_hls, verify_remote
from job_route import hls_prefix, job_table, updates_episode
from key_provider import PlaintextKeyStore, key_uri
from retry_policy import (INPUT_INVALID, NO_VIDEO_STREAM, OUTPUT_INVALID, SOURCE_MISSING, STORAGE_ERROR, TIMEOUT,
                          TRANSCODE_FAILED, FenceLost, JobError, plan_failure)


def env_int(name, default):
    return int(os.environ.get(name) or default)


def env_float(name, default):
    return float(os.environ.get(name) or default)


WORKER_ID = f"{socket.gethostname()}-{os.getpid()}"
# 以下都是可调配置项（环境变量），默认值只是开发默认，不是基准或承诺。
LEASE_SEC = env_int("WORKER_LEASE_SECONDS", 1800)            # 租约时长
HEARTBEAT_SEC = env_int("WORKER_HEARTBEAT_SECONDS", 60)      # 续租间隔，须明显小于租约
if HEARTBEAT_SEC * 3 > LEASE_SEC:                            # 至少留两次续租机会
    HEARTBEAT_SEC = max(1, LEASE_SEC // 3)
FFPROBE_TIMEOUT_SEC = env_int("WORKER_FFPROBE_TIMEOUT_SECONDS", 120)
FFMPEG_TIMEOUT_SEC = env_int("WORKER_FFMPEG_TIMEOUT_SECONDS", 3600)
RETRY_BASE_MS = env_int("WORKER_RETRY_BASE_MS", 10_000)      # 第 1 次失败后的退避上限
RETRY_MAX_MS = env_int("WORKER_RETRY_MAX_MS", 600_000)       # 退避封顶
RETRY_JITTER = env_float("WORKER_RETRY_JITTER", 0.5)         # 退避随机缩短比例 0~1
REQUEUE_DELAY_MS = env_int("WORKER_REQUEUE_DELAY_MS", 60_000)  # 别人租约内：多久后再看
SWEEP_INTERVAL_SEC = env_int("WORKER_SWEEP_INTERVAL_SECONDS", 60)
SWEEP_PENDING_AGE_SEC = env_int("WORKER_SWEEP_PENDING_AGE_SECONDS", 120)
METRICS_PORT = env_int("WORKER_METRICS_PORT", 9100)
# 垃圾输出前缀清理（Phase 4）。宽限期须大于租约与播放 URL/token 的最长有效期（vod.playback.token-ttl-max-seconds）。
OUTPUT_GC_ENABLED = os.environ.get("WORKER_OUTPUT_GC_ENABLED", "1").strip().lower() not in ("0", "false", "no", "off")
OUTPUT_GC_GRACE_SEC = env_int("WORKER_OUTPUT_GC_GRACE_SECONDS", 86_400)
OUTPUT_GC_INTERVAL_SEC = env_int("WORKER_OUTPUT_GC_INTERVAL_SECONDS", 300)
OUTPUT_GC_PREFIX_BATCH = env_int("WORKER_OUTPUT_GC_PREFIX_BATCH", 20)    # 每轮最多处理几个前缀
OUTPUT_GC_DELETE_BATCH = env_int("WORKER_OUTPUT_GC_DELETE_BATCH", 500)   # 每次 list/delete_objects 的对象数（≤1000）
POLL_SEC = 0.5                                               # 子进程轮询间隔：围栏丢失后多快杀掉 ffmpeg

FIRST_SEG, SEG = 1, 4   # 首片 1s（秒开），之后 4s
# 码率阶梯：(名字, 短边像素, 视频码率kbps)。竖屏短剧 720p = 720x1280。另一条边按比例算(-2 保证偶数)
LADDER = [("720p", 720, 2500), ("540p", 540, 1200), ("360p", 360, 600)]

JOBS = Counter("vod_transcode_jobs_total", "transcode job results", ["result"])
DUR = Histogram("vod_transcode_seconds", "transcode wall time", buckets=(5, 10, 30, 60, 120, 300, 600, 1800))
INFLIGHT = Gauge("vod_transcode_inflight", "jobs running in this worker")
HEARTBEAT = Gauge("vod_worker_heartbeat_timestamp_seconds",
                  "unix time of the last successful lease renewal or sweeper DB pass")
LEASE_LOST = Counter("vod_worker_lease_lost_total", "heartbeat found the claim fence lost (taken over or cancelled)")
DROP_FENCED = Counter("vod_worker_drop_fenced_total", "results dropped because the claim fence was lost")
GC_PREFIXES = Counter("vod_worker_output_gc_prefixes_total", "garbage output prefixes deleted")

KEYS = PlaintextKeyStore()

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


def renew_lease(job_id, version, origin="legacy"):
    conn = db()
    try:
        with conn.cursor() as c:
            return renew_lease_sql(c, origin, job_id, version, LEASE_SEC)
    finally:
        conn.close()


def run(cmd, timeout=None, stop=None, fail_code=TRANSCODE_FAILED):
    """跑子进程。超时杀掉并抛 JobError(TIMEOUT)；stop 被置位（围栏丢失）时杀掉并抛 FenceLost。"""
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    deadline = time.monotonic() + timeout if timeout else None
    while True:
        try:
            out, err = p.communicate(timeout=POLL_SEC)
            break
        except subprocess.TimeoutExpired:
            if stop is not None and stop.is_set():
                p.kill()
                p.communicate()
                raise FenceLost(f"{cmd[0]} killed: fence lost")
            if deadline is not None and time.monotonic() >= deadline:
                p.kill()
                p.communicate()
                raise JobError(TIMEOUT, f"{cmd[0]} timed out after {timeout}s")
    if p.returncode != 0:
        raise JobError(fail_code, f"{cmd[0]} failed: {err[-800:]}")
    return out


def probe(path, stop=None):
    out = run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path],
              FFPROBE_TIMEOUT_SEC, stop, INPUT_INVALID)
    try:
        info = json.loads(out)
        v = next((s for s in info.get("streams", []) if s.get("codec_type") == "video"), None)
    except (ValueError, AttributeError) as e:
        raise JobError(INPUT_INVALID, f"ffprobe output unreadable: {e}") from e
    if not v:
        raise JobError(NO_VIDEO_STREAM, "no video stream")
    try:
        has_audio = any(s.get("codec_type") == "audio" for s in info["streams"])
        return int(v["width"]), int(v["height"]), int(float(info["format"]["duration"]) * 1000), has_audio
    except (KeyError, TypeError, ValueError) as e:
        raise JobError(INPUT_INVALID, f"ffprobe missing field: {e}") from e


def download_source(bucket, key, dest):
    try:
        s3c.download_file(bucket, key, dest)
    except Exception as e:
        resp = getattr(e, "response", None)
        code = str(resp.get("Error", {}).get("Code", "")) if isinstance(resp, dict) else ""
        if code in ("404", "NoSuchKey"):
            raise JobError(SOURCE_MISSING, f"source object missing: {bucket}/{key}") from e
        raise


def build_ffmpeg(src, out, ladder, has_audio, keyinfo, portrait, audio_kbps=96):
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
        cmd += ["-c:a", "aac", "-b:a", f"{int(audio_kbps)}k", "-ac", "2", "-ar", "48000"]
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


def get_or_create_key(c, asset_id, origin="legacy"):
    """旧 Demo 用 content_key，Core 用 vod_content_key（两套资产 id 会撞号，不能共表）。"""
    return KEYS.get_or_create(c, origin, asset_id)


def remote_listing(prefix):
    """前缀下的远端对象 {键: 大小}，分页。"""
    out, token = {}, None
    while True:
        kw = {"Bucket": HLS_BUCKET, "Prefix": prefix}
        if token:
            kw["ContinuationToken"] = token
        page = s3c.list_objects_v2(**kw)
        for o in page.get("Contents", []):
            out[o["Key"]] = o["Size"]
        if not page.get("IsTruncated"):
            return out
        token = page.get("NextContinuationToken")


def check_remote(local_root, prefix):
    try:
        listing = remote_listing(prefix)
    except Exception as e:
        raise JobError(STORAGE_ERROR, f"list {prefix} failed: {e}") from e
    try:
        verify_remote(local_manifest(local_root), listing, prefix)
    except RuntimeError as e:
        raise JobError(STORAGE_ERROR, f"remote verify {prefix}: {e}") from e


def upload_dir(local, prefix, before_master=None, stop=None):
    """master 最后传；before_master 在传 master 之前调用，抛异常就不传 master。
    stop（心跳发现围栏丢失）被置位后不再传后续文件，减少写进已被判为垃圾的前缀的对象。"""
    files = sorted(os.path.relpath(os.path.join(d, f), local) for d, _, fs in os.walk(local) for f in fs)
    files.sort(key=lambda f: f == "master.m3u8")  # master 最后传
    for rel in files:
        if stop is not None and stop.is_set():
            raise FenceLost("fence lost during upload")
        if rel == "master.m3u8" and before_master:
            before_master()
        ctype = "application/vnd.apple.mpegurl" if rel.endswith(".m3u8") else (
            mimetypes.guess_type(rel)[0] or "video/mp2t")
        s3c.upload_file(os.path.join(local, rel), HLS_BUCKET, prefix + rel, ExtraArgs={"ContentType": ctype})
    return len(files)


def renditions_of(ladder, w, h, portrait):
    out = []
    for name, p, kbps in ladder:
        other = round(max(w, h) * p / min(w, h) / 2) * 2
        rw, rh = (p, other) if portrait else (other, p)
        out.append((name, rw, rh, kbps))
    return out


def drop_fenced(job_id, version, trace_id, reason):
    DROP_FENCED.inc()
    log("drop_fenced", job_id=job_id, claim_version=version, reason=reason, trace_id=trace_id)
    return "drop"


def process(job_id, trace_id, origin="legacy"):
    table = job_table(origin)
    conn = db()
    c = conn.cursor()
    if not claim(c, origin, job_id, WORKER_ID, LEASE_SEC):
        c.execute(f"SELECT status, locked_until FROM {table} WHERE id=%s", (job_id,))
        row = c.fetchone()
        conn.close()
        log("skip_duplicate", job_id=job_id, status=row and row["status"], trace_id=trace_id, origin=origin)
        # 别人正在跑（租约未到期，含消息被重投时原进程仍活着）：延迟再看；已终态/已取消：丢弃
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
    if mark_transcoding(conn, c, origin, asset_id, job_id, version) != 1:
        conn.close()
        return drop_fenced(job_id, version, trace_id, "asset_transcoding")
    prefix = hls_prefix(origin, asset_id, job_id, version)  # 旧 Demo 仍是固定 assets/{id}/
    prefix_recorded = False
    lost, done = threading.Event(), threading.Event()

    def beat():
        while not done.wait(HEARTBEAT_SEC):
            try:
                held = renew_lease(job_id, version, origin)
            except Exception as e:  # 数据库暂时连不上：下次再续，上传前还会同步复核
                log("heartbeat_error", job_id=job_id, err=str(e)[:200], trace_id=trace_id)
                continue
            if held:
                HEARTBEAT.set_to_current_time()
                continue
            lost.set()
            LEASE_LOST.inc()
            log("lease_lost", job_id=job_id, claim_version=version, trace_id=trace_id)
            return

    def check_fence():
        """同步复核：不只看心跳线程的事件，当场再续一次租，失败就停。"""
        if lost.is_set() or not renew_lease(job_id, version, origin):
            lost.set()
            raise FenceLost("fence lost before upload")

    threading.Thread(target=beat, daemon=True).start()
    work = f"/tmp/job_{job_id}"
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f"{work}/out")
    t0 = time.time()
    INFLIGHT.inc()
    log("job_start", job_id=job_id, asset_id=asset_id, attempt=a["attempts"], claim_version=version,
        origin=origin, trace_id=trace_id, worker=WORKER_ID)
    try:
        src = f"{work}/source"
        download_source(a["source_bucket"], a["source_key"], src)
        w, h, dur_ms, has_audio = probe(src, lost)
        portrait = h >= w
        short = min(w, h)
        keyinfo = None
        if a["encrypted"]:  # 重新转码时 INSERT IGNORE 复用已有的 key，不轮换
            k = get_or_create_key(c, asset_id, origin)
            with open(f"{work}/enc.key", "wb") as f:
                f.write(bytes.fromhex(k["key_hex"]))
            keyinfo = f"{work}/keyinfo"
            with open(keyinfo, "w") as f:  # 三行：写进 m3u8 的 key URI / 本地 key 文件 / IV
                f.write(f"{key_uri(origin, asset_id)}\n{work}/enc.key\n{k['iv_hex']}\n")
        # Phase 5：Core 按 job_id 读模板（消息里没有梯队）；旧 Demo 仍是冻结的 LADDER。
        base_ladder, audio_kbps = transcode_template.load(c, job_id) if origin == "core" else (LADDER, 96)
        ladder = [x for x in base_ladder if x[1] <= short]
        if not ladder:  # 小于 360 也不放大，按源的短边出一档
            even = max(2, short // 2 * 2)
            ladder = [(f"{even}p", even, 600)]
        run(build_ffmpeg(src, f"{work}/out", ladder, has_audio, keyinfo, portrait, audio_kbps), FFMPEG_TIMEOUT_SEC, lost)
        try:
            verify_hls(f"{work}/out", [name for name, _, _ in ladder], bool(a["encrypted"]))
        except RuntimeError as e:
            raise JobError(OUTPUT_INVALID, str(e)) from e
        check_fence()
        if origin == "core":
            output_prefix.insert_writing(c, asset_id, job_id, version, prefix)
            prefix_recorded = True
        nfiles = upload_dir(f"{work}/out", prefix, before_master=check_fence, stop=lost)
        if origin == "core":
            check_remote(f"{work}/out", prefix)
        conn.begin()
        if not commit_success(c, origin, job_id, version, asset_id, prefix, dur_ms, w, h,
                              renditions_of(ladder, w, h, portrait), a["episode_id"]):
            conn.rollback()
            discard_prefix(c, prefix, prefix_recorded)
            return drop_fenced(job_id, version, trace_id, "commit_success")
        conn.commit()
        if a.get("episode_id"):
            r.delete(f"ep:{a['episode_id']}")  # 缓存失效：下次播放读到新 asset_id
        JOBS.labels("succeeded").inc()
        DUR.observe(time.time() - t0)
        log("job_done", job_id=job_id, asset_id=asset_id, renditions=[x[0] for x in ladder], files=nfiles,
            claim_version=version, secs=round(time.time() - t0, 1), trace_id=trace_id)
        return "ok"
    except FenceLost as e:
        conn.rollback()
        discard_prefix(c, prefix, prefix_recorded)
        return drop_fenced(job_id, version, trace_id, str(e))
    except Exception as e:
        conn.rollback()
        final, code, delay = plan_failure(origin, a["attempts"], a["max_attempts"], e,
                                          RETRY_BASE_MS, RETRY_MAX_MS, RETRY_JITTER)
        conn.begin()
        changed = record_failure(c, origin, job_id, version, final, str(e)[-2000:], code, delay)
        if not apply_failure(c, origin, asset_id, job_id, version, changed, final):
            conn.rollback()
            discard_prefix(c, prefix, prefix_recorded)
            return drop_fenced(job_id, version, trace_id, "record_failure")
        if prefix_recorded:
            output_prefix.discard(c, prefix)  # 同一事务：重试会换新的 claim_version，也就换新前缀
        conn.commit()
        JOBS.labels("failed" if final else "retry").inc()
        log("job_error", job_id=job_id, attempt=a["attempts"], final=final, error_code=code, delay_ms=delay,
            err=str(e)[-300:], trace_id=trace_id)
        return "dlq" if final else ("retry", delay)
    finally:
        done.set()
        INFLIGHT.dec()
        conn.close()
        shutil.rmtree(work, ignore_errors=True)


def discard_prefix(c, prefix, recorded):
    """没提交成功的前缀置 GARBAGE（自动提交）。失败只打日志：清理线程也会按 claim_version 认出过时的 WRITING。"""
    if not recorded:
        return
    try:
        output_prefix.discard(c, prefix)
    except Exception as e:
        log("prefix_discard_error", prefix=prefix, err=str(e)[:200])


def finish(ch, method, msg, res):
    if res == "requeue":
        publish(ch, Q_RETRY, msg, delay_ms=REQUEUE_DELAY_MS)
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
            # 重投（method.redelivered）不再抢占：原进程可能还活着，只有租约过期才能接手
            res = process(msg["job_id"], msg.get("trace_id"), msg.get("origin") or "legacy")
        except Exception as e:  # 比如数据库连不上：稍后重试
            log("process_crash", job_id=msg.get("job_id"), err=str(e))
            res = "requeue"
        try:
            conn.add_callback_threadsafe(lambda: finish(ch, method, msg, res))
        except Exception as e:  # 连接已断：消息会被重投，状态以数据库为准
            log("ack_lost", job_id=msg.get("job_id"), err=str(e)[:200])

    threading.Thread(target=work, daemon=True).start()


def sweeper():
    """补偿扫描：outbox 之外的兜底。PENDING 太久没人领、RUNNING 租约过期、
    Core 的 RETRYING 过了 next_attempt_at（延迟重试消息丢了）都重新投递；CANCELLED 和终态不碰。"""
    while True:
        try:
            conn = db()
            with conn.cursor() as c:
                rows = due_jobs(c, "legacy", SWEEP_PENDING_AGE_SEC)
            conn.close()
            HEARTBEAT.set_to_current_time()
            core_rows = []
            try:
                c2 = db()
                with c2.cursor() as cur:
                    core_rows = due_jobs(cur, "core", SWEEP_PENDING_AGE_SEC)
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
        time.sleep(SWEEP_INTERVAL_SEC)


def output_gc_loop():
    """删除过了宽限期的垃圾输出前缀（只有 Core 任务专属前缀）。见 output_gc.py。"""
    while True:
        time.sleep(OUTPUT_GC_INTERVAL_SEC)
        try:
            conn = db()
            try:
                with conn.cursor() as c:
                    n, objects, skipped = output_gc.gc_pass(c, s3c, HLS_BUCKET, OUTPUT_GC_GRACE_SEC,
                                                            OUTPUT_GC_PREFIX_BATCH, OUTPUT_GC_DELETE_BATCH, log)
            finally:
                conn.close()
            if n:
                GC_PREFIXES.inc(n)
                log("output_gc_pass", prefixes=n, objects=objects, skipped=skipped)
        except Exception as e:
            log("output_gc_error", err=str(e)[:300])


def gc_grace_seconds(grace, lease):
    """宽限期不能短于租约：前缀被判成垃圾时，原来的写入方可能还没发现围栏丢失、仍在往里传文件。"""
    return max(int(grace), int(lease))


def main():
    global OUTPUT_GC_GRACE_SEC
    start_http_server(METRICS_PORT)
    wait_for("mysql", lambda: db().close())
    ensure_claim_version()
    threading.Thread(target=sweeper, daemon=True).start()
    if OUTPUT_GC_ENABLED:
        if OUTPUT_GC_GRACE_SEC < LEASE_SEC:
            log("output_gc_grace_raised", configured=OUTPUT_GC_GRACE_SEC, lease_seconds=LEASE_SEC)
        OUTPUT_GC_GRACE_SEC = gc_grace_seconds(OUTPUT_GC_GRACE_SEC, LEASE_SEC)
        threading.Thread(target=output_gc_loop, daemon=True).start()
    while True:  # RabbitMQ 重启或断线后重连；在跑的任务靠租约和围栏收尾
        mconn, ch = wait_for("rabbitmq", mq_channel)
        ch.basic_qos(prefetch_count=1)  # 一个 worker 同时只拿一条，转码是 CPU 密集，靠多开 worker 横向扩
        ch.basic_consume(Q_MAIN, on_message)
        log("worker_started", worker=WORKER_ID, lease_seconds=LEASE_SEC, heartbeat_seconds=HEARTBEAT_SEC,
            output_gc=OUTPUT_GC_ENABLED, output_gc_grace_seconds=OUTPUT_GC_GRACE_SEC)
        try:
            ch.start_consuming()
        except pika.exceptions.AMQPError as e:
            log("mq_disconnected", err=str(e)[:200])
            time.sleep(2)


if __name__ == "__main__":
    main()
