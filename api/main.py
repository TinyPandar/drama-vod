"""短剧点播 MVP 的业务 API + key 服务。

接口一览（经 nginx :8080 访问）：
  POST /api/dramas                         建剧
  POST /api/dramas/{id}/episodes           建集（is_paid=1 表示付费集，转码时会 AES-128 加密）
  POST /api/episodes/{id}/upload-url       申请预签名直传 URL（客户端直接 PUT 到对象存储）
  POST /api/assets/{id}/complete           客户端上传完回调 -> 建转码任务 -> 投递 MQ
  GET  /api/assets/{id}                    查资产/转码状态
  POST /api/users/{uid}/unlock             模拟购买解锁
  GET  /api/episodes/{id}/play?user_id=    鉴权后发签名播放地址 + 播放 token（还带下一集，供预加载）
  GET  /keys/{asset_id}                    key 服务：凭播放 token 下发 AES-128 密钥
  GET  /api/rank/hot  /api/episodes/{id}/stats  /metrics
"""
import base64, hashlib, hmac, json, os, threading, time

from fastapi import FastAPI, Header, HTTPException, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel

from common import (ENV, HLS_BUCKET, Q_MAIN, SOURCE_BUCKET, db, log, mq_channel, publish, rds, s3,
                    wait_for)

HLS_SECRET = ENV("HLS_SECRET", "dev")
TOKEN_SECRET = ENV("TOKEN_SECRET", "dev").encode()
PLAY_URL_TTL = int(ENV("PLAY_URL_TTL", "600"))
PUBLIC_UPLOAD_ENDPOINT = ENV("PUBLIC_UPLOAD_ENDPOINT", "http://localhost:9000")

app = FastAPI(title="drama-vod api")
s3_internal = s3()
s3_public = s3(PUBLIC_UPLOAD_ENDPOINT)  # 只用来"算签名"，不发请求；host 必须是客户端真正访问的地址
r = rds()

# ---------------- 指标 ----------------
HTTP_LAT = Histogram("vod_http_request_seconds", "API latency", ["method", "route", "status"])
PLAY_REQ = Counter("vod_play_requests_total", "play url requests", ["result"])
KEY_REQ = Counter("vod_key_requests_total", "AES key requests", ["result"])
UPLOAD_DONE = Counter("vod_upload_complete_total", "upload complete callbacks", ["result"])


@app.middleware("http")
async def metrics_mw(request: Request, call_next):
    t0 = time.perf_counter()
    resp = await call_next(request)
    route = request.scope.get("route")
    HTTP_LAT.labels(request.method, getattr(route, "path", "other"), resp.status_code).observe(
        time.perf_counter() - t0)
    return resp


@app.get("/metrics")
def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.get("/healthz")
def healthz():
    return {"ok": True}


# ---------------- 启动：建桶 + 播放计数回刷线程 ----------------
@app.on_event("startup")
def startup():
    def ensure_buckets():
        existing = {b["Name"] for b in s3_internal.list_buckets()["Buckets"]}
        for b in (SOURCE_BUCKET, HLS_BUCKET):
            if b not in existing:
                s3_internal.create_bucket(Bucket=b)
        # HLS 桶允许匿名读：MinIO 不对外暴露，只有 nginx(模拟 CDN) 在内网回源。
        # 生产等价物：私有桶 + CDN 回源鉴权（OSS 私有 Bucket 回源 / CloudFront OAC）。
        s3_internal.put_bucket_policy(Bucket=HLS_BUCKET, Policy=json.dumps({
            "Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Principal": {"AWS": ["*"]},
                                                    "Action": ["s3:GetObject"],
                                                    "Resource": [f"arn:aws:s3:::{HLS_BUCKET}/*"]}]}))
    wait_for("minio", ensure_buckets)
    wait_for("mysql", lambda: db().close())
    threading.Thread(target=flush_play_counts_loop, daemon=True).start()
    log("api_started")


def flush_play_counts_loop():
    """Redis 扛实时 INCR，每 30s 用 GETDEL 原子取走增量，批量累加进 MySQL。"""
    while True:
        time.sleep(30)
        try:
            flush_play_counts()
        except Exception as e:
            log("flush_play_counts_error", err=str(e))


def flush_play_counts():
    conn = db()
    n = 0
    with conn.cursor() as c:
        for key in r.scan_iter("play:cnt:*"):
            delta = r.getdel(key)
            if delta:
                c.execute("INSERT INTO episode_play_stat(episode_id, play_count) VALUES(%s,%s) "
                          "ON DUPLICATE KEY UPDATE play_count = play_count + VALUES(play_count)",
                          (int(key.rsplit(":", 1)[1]), int(delta)))
                n += 1
    conn.close()
    return n


# ---------------- 内容管理 ----------------
class DramaIn(BaseModel):
    title: str


class EpisodeIn(BaseModel):
    ep_no: int
    title: str = ""
    is_paid: bool = False
    price_coins: int = 0


@app.post("/api/dramas")
def create_drama(body: DramaIn):
    conn = db()
    with conn.cursor() as c:
        c.execute("INSERT INTO drama(title) VALUES(%s)", (body.title,))
        did = c.lastrowid
    conn.close()
    return {"drama_id": did}


@app.post("/api/dramas/{drama_id}/episodes")
def create_episode(drama_id: int, body: EpisodeIn):
    conn = db()
    with conn.cursor() as c:
        c.execute("INSERT INTO episode(drama_id, ep_no, title, is_paid, price_coins) VALUES(%s,%s,%s,%s,%s)",
                  (drama_id, body.ep_no, body.title, int(body.is_paid), body.price_coins))
        eid = c.lastrowid
        c.execute("UPDATE drama SET total_episodes = (SELECT COUNT(*) FROM episode WHERE drama_id=%s) "
                  "WHERE id=%s", (drama_id, drama_id))
    conn.close()
    return {"episode_id": eid}


# ---------------- 上传：预签名直传 ----------------
@app.post("/api/episodes/{episode_id}/upload-url")
def upload_url(episode_id: int):
    ep = get_episode(episode_id)
    conn = db()
    with conn.cursor() as c:
        c.execute("INSERT INTO video_asset(episode_id, source_bucket, source_key, encrypted) VALUES(%s,%s,'',%s)",
                  (episode_id, SOURCE_BUCKET, ep["is_paid"]))
        asset_id = c.lastrowid
        # 目录按 剧/集/资产 分层；资产 id 保证同一集重传不会覆盖旧原片
        key = f"raw/{ep['drama_id']}/{episode_id}/{asset_id}/source.mp4"
        c.execute("UPDATE video_asset SET source_key=%s WHERE id=%s", (key, asset_id))
    conn.close()
    put_url = s3_public.generate_presigned_url("put_object", Params={"Bucket": SOURCE_BUCKET, "Key": key},
                                               ExpiresIn=900)
    return {"asset_id": asset_id, "put_url": put_url, "expires_in": 900}


@app.post("/api/assets/{asset_id}/complete")
def upload_complete(asset_id: int, x_request_id: str | None = Header(default=None)):
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT * FROM video_asset WHERE id=%s", (asset_id,))
        a = c.fetchone()
        if not a:
            raise HTTPException(404, "asset not found")
        try:  # 不信客户端：自己去对象存储确认文件真的在
            head = s3_internal.head_object(Bucket=a["source_bucket"], Key=a["source_key"])
        except Exception:
            UPLOAD_DONE.labels("missing").inc()
            raise HTTPException(409, "source object not found, upload first")
        c.execute("UPDATE video_asset SET status='UPLOADED', source_size=%s WHERE id=%s AND status='UPLOADING'",
                  (head["ContentLength"], asset_id))
        # INSERT IGNORE + 唯一键：回调重复调用也只会有一个任务
        c.execute("INSERT IGNORE INTO transcode_job(asset_id, profile, trace_id) VALUES(%s,'ladder_v1',%s)",
                  (asset_id, x_request_id))
        c.execute("SELECT id, status FROM transcode_job WHERE asset_id=%s AND profile='ladder_v1'", (asset_id,))
        job = c.fetchone()
    conn.close()
    if job["status"] == "PENDING":
        # 先落库再发消息；发失败也没关系，worker 的补偿扫描会把久未开始的 PENDING 任务重新投递
        try:
            mconn, ch = mq_channel()
            publish(ch, Q_MAIN, {"job_id": job["id"], "asset_id": asset_id, "trace_id": x_request_id})
            mconn.close()
        except Exception as e:
            log("publish_failed", job_id=job["id"], err=str(e))
    UPLOAD_DONE.labels("ok").inc()
    log("upload_complete", asset_id=asset_id, job_id=job["id"], trace_id=x_request_id)
    return {"asset_id": asset_id, "job_id": job["id"], "job_status": job["status"]}


@app.get("/api/assets/{asset_id}")
def asset_status(asset_id: int):
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT id, episode_id, status, duration_ms, width, height, encrypted, hls_prefix "
                  "FROM video_asset WHERE id=%s", (asset_id,))
        a = c.fetchone()
        c.execute("SELECT id, status, attempts, last_error, worker_id FROM transcode_job WHERE asset_id=%s",
                  (asset_id,))
        jobs = c.fetchall()
        c.execute("SELECT name, width, height, video_kbps FROM asset_rendition WHERE asset_id=%s ORDER BY height DESC",
                  (asset_id,))
        rend = c.fetchall()
    conn.close()
    if not a:
        raise HTTPException(404)
    return {"asset": a, "jobs": jobs, "renditions": rend}


# ---------------- 付费 / 解锁 ----------------
class UnlockIn(BaseModel):
    episode_id: int
    order_no: str | None = None


@app.post("/api/users/{user_id}/unlock")
def unlock(user_id: int, body: UnlockIn):
    # 真实场景：先扣金币/支付回调成功，再在同一事务里写 unlock；这里直接写
    conn = db()
    with conn.cursor() as c:
        c.execute("INSERT IGNORE INTO user_unlock(user_id, episode_id, order_no) VALUES(%s,%s,%s)",
                  (user_id, body.episode_id, body.order_no))
    conn.close()
    r.sadd(f"unlock:{user_id}", body.episode_id)
    return {"ok": True}


def can_play(user_id: int, ep: dict) -> bool:
    if not ep["is_paid"]:
        return True
    if r.sismember(f"unlock:{user_id}", ep["id"]):
        return True
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT 1 FROM user_unlock WHERE user_id=%s AND episode_id=%s", (user_id, ep["id"]))
        ok = c.fetchone() is not None
    conn.close()
    if ok:
        r.sadd(f"unlock:{user_id}", ep["id"])
    return ok


# ---------------- 播放 ----------------
def get_episode(episode_id: int) -> dict:
    """cache-aside：先读 Redis，miss 再查库回填；转码完成时 worker 会删这个 key。"""
    ck = f"ep:{episode_id}"
    cached = r.get(ck)
    if cached:
        return json.loads(cached)
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT id, drama_id, ep_no, title, is_paid, price_coins, asset_id, status FROM episode WHERE id=%s",
                  (episode_id,))
        ep = c.fetchone()
    conn.close()
    if not ep:
        raise HTTPException(404, "episode not found")
    r.set(ck, json.dumps(ep), ex=60)
    return ep


def sign_hls_path(asset_id: int, expires: int) -> str:
    """nginx secure_link：md5( 过期时间 + "/" + 资产目录 + " " + 密钥 )，base64url。
    签的是"整个资产目录"而不是单个文件，所以 m3u8 里的相对路径分片会自动带上同一个签名前缀。"""
    raw = f"{expires}/{asset_id} {HLS_SECRET}".encode()
    sig = base64.urlsafe_b64encode(hashlib.md5(raw).digest()).decode().rstrip("=")
    return f"/hls/{sig}/{expires}/{asset_id}/master.m3u8"


def make_token(user_id: int, asset_id: int, exp: int) -> str:
    payload = base64.urlsafe_b64encode(json.dumps({"u": user_id, "a": asset_id, "exp": exp}).encode()).decode()
    sig = hmac.new(TOKEN_SECRET, payload.encode(), hashlib.sha256).hexdigest()
    return f"{payload}.{sig}"


def verify_token(token: str) -> dict | None:
    try:
        payload, sig = token.split(".")
        if not hmac.compare_digest(sig, hmac.new(TOKEN_SECRET, payload.encode(), hashlib.sha256).hexdigest()):
            return None
        data = json.loads(base64.urlsafe_b64decode(payload))
        return data if data["exp"] >= time.time() else None
    except Exception:
        return None


def play_info(user_id: int, ep: dict) -> dict:
    exp = int(time.time()) + PLAY_URL_TTL
    return {"episode_id": ep["id"], "asset_id": ep["asset_id"], "encrypted": bool(ep["is_paid"]),
            "master_url": sign_hls_path(ep["asset_id"], exp),
            "token": make_token(user_id, ep["asset_id"], exp), "expires_at": exp}


@app.get("/api/episodes/{episode_id}/play")
def play(episode_id: int, user_id: int):
    ep = get_episode(episode_id)
    if ep["status"] != "READY" or not ep["asset_id"]:
        PLAY_REQ.labels("not_ready").inc()
        raise HTTPException(409, "episode not ready (still transcoding?)")
    if not can_play(user_id, ep):
        PLAY_REQ.labels("need_unlock").inc()
        raise HTTPException(402, "paid episode, unlock first")
    info = play_info(user_id, ep)
    # 下一集：能播才给地址，客户端用来预加载 master + 首片
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT id FROM episode WHERE drama_id=%s AND ep_no=%s", (ep["drama_id"], ep["ep_no"] + 1))
        nxt = c.fetchone()
    conn.close()
    info["next"] = None
    if nxt:
        nep = get_episode(nxt["id"])
        if nep["status"] == "READY" and nep["asset_id"]:
            info["next"] = play_info(user_id, nep) if can_play(user_id, nep) else {
                "episode_id": nep["id"], "locked": True}
    pipe = r.pipeline()
    pipe.incr(f"play:cnt:{episode_id}")
    pipe.zincrby(f"rank:hot:{time.strftime('%Y%m%d')}", 1, ep["drama_id"])
    pipe.execute()
    PLAY_REQ.labels("ok").inc()
    return info


@app.get("/keys/{asset_id}")
def get_key(asset_id: int, authorization: str | None = Header(default=None)):
    """播放器拿 m3u8 里的 #EXT-X-KEY URI 来请求这里。没有合法 token 不给 key，分片偷走了也解不开。"""
    tok = verify_token((authorization or "").removeprefix("Bearer ").strip())
    if not tok or tok["a"] != asset_id:
        KEY_REQ.labels("denied").inc()
        raise HTTPException(401, "invalid or expired play token")
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT key_hex FROM content_key WHERE asset_id=%s", (asset_id,))
        row = c.fetchone()
    conn.close()
    if not row:
        KEY_REQ.labels("not_found").inc()
        raise HTTPException(404)
    KEY_REQ.labels("ok").inc()
    return Response(bytes.fromhex(row["key_hex"]), media_type="application/octet-stream",
                    headers={"Cache-Control": "no-store"})


# ---------------- 统计 ----------------
@app.get("/api/episodes/{episode_id}/stats")
def stats(episode_id: int):
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT play_count FROM episode_play_stat WHERE episode_id=%s", (episode_id,))
        row = c.fetchone()
    conn.close()
    return {"episode_id": episode_id, "flushed_to_mysql": row["play_count"] if row else 0,
            "pending_in_redis": int(r.get(f"play:cnt:{episode_id}") or 0)}


@app.post("/api/admin/flush-play-counts")
def admin_flush():
    return {"flushed_keys": flush_play_counts()}


@app.get("/api/rank/hot")
def hot_rank(top: int = 10):
    return r.zrevrange(f"rank:hot:{time.strftime('%Y%m%d')}", 0, top - 1, withscores=True)
