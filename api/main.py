"""短剧点播 MVP 的业务 API + key 服务。

接口一览（经 nginx :8080 访问）：
  POST /api/dramas                         建剧
  GET  /api/dramas                         上架剧列表（播放页）
  GET  /api/dramas/{id}                    剧详情 + 分集
  POST /api/dramas/{id}/episodes           建集（is_paid=1 表示付费集，转码时会 AES-128 加密）
  GET  /api/episodes/{id}                  集详情（含所属剧）
  POST /api/episodes/{id}/upload-url       申请预签名直传 URL（客户端直接 PUT 到对象存储）
  POST /api/assets/{id}/complete           客户端上传完回调 -> 建转码任务 -> 投递 MQ
  GET  /api/assets/{id}                    查资产/转码状态
  POST /api/users/{uid}/unlock             模拟购买解锁
  GET  /api/episodes/{id}/play?user_id=    鉴权后发签名播放地址 + 播放 token（还带下一集，供预加载）
  GET  /keys/{asset_id}                    key 服务：凭播放 token 下发 AES-128 密钥
  GET  /api/playback/{asset}/master.m3u8   给不能设请求头的播放器（iPhone）改写播放列表
  GET  /api/episodes/{id}/vod-play?user_id=  Demo 解锁后经视频云签 Core 播放 token（Core 的 key 走 /keys/v1/，在 vod-api）
  GET  /api/rank/hot  /api/episodes/{id}/stats  /metrics

路线 C（Demo 改为视频云的客户）：
  POST /api/auth/register  /api/auth/login   观众注册、登录，返回 Demo 会话 token（Authorization: Bearer dsess1.…）
  GET  /api/me                               当前观众、金币余额、已解锁的集
  POST /api/users/{uid}/unlock               需要本人的会话；付费集按 price_coins 扣金币，重复解锁不重复扣
  upload-url / complete / GET /api/assets/{id} 改为调视频云（Core）：付费集上传成 Core 加密资产，
                                             转码完成后由后台对账线程把这一集切到新的 Core 资产
  play / vod-play                            一律经视频云签 Core 播放 token；还没迁移的旧资产暂时走旧链路
旧的 /keys/{id}、/api/playback/{asset}/…、/hls/ 只为切换前已经发出的旧地址保留，新的播放不再产生它们。
"""
import base64, hashlib, hmac, json, os, re, threading, time
from urllib.parse import quote

from fastapi import FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import JSONResponse, StreamingResponse
from origin_path import object_key
import accounts
import secret_guard
import vod_client
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel

from common import ENV, HLS_BUCKET, SOURCE_BUCKET, db, log, rds, s3, wait_for

VOD_ENV = ENV("VOD_ENV", "dev")
MIN_SECRET_LENGTH = int(ENV("VOD_MIN_SECRET_LENGTH") or 32)
HLS_SECRET = ENV("HLS_SECRET", "dev")
TOKEN_SECRET = ENV("TOKEN_SECRET", "dev").encode()
PLAY_URL_TTL = int(ENV("PLAY_URL_TTL", "600"))
PUBLIC_UPLOAD_ENDPOINT = ENV("PUBLIC_UPLOAD_ENDPOINT", "http://localhost:9000")
# compose 不再给 :-dev 兜底：dev 下为空时在这里退回 dev（Nginx 的入口脚本同样处理），非 dev 由启动检查拒绝。
ORIGIN_SECRET = secret_guard.dev_fallback(ENV("ORIGIN_SECRET", ""), VOD_ENV)
# 路线 C：Demo 观众会话的 HMAC 密钥（和旧播放 token、Core 播放 token 分开）。dev 下为空时退回 dev。
DEMO_SESSION_SECRET = secret_guard.dev_fallback(ENV("DEMO_SESSION_SECRET", ""), VOD_ENV)
DEMO_SESSION_TTL = int(ENV("DEMO_SESSION_TTL_SECONDS") or 7 * 86400)
DEMO_SIGNUP_COINS = int(ENV("DEMO_SIGNUP_COINS") or 100)
# 过渡开关：1 = 没有会话时仍按 ?user_id= 认人（路线 C 之前的行为，不安全）。默认关。
DEMO_ALLOW_USER_ID_PARAM = (ENV("DEMO_ALLOW_USER_ID_PARAM") or "0").strip() == "1"
DEMO_SYNC_INTERVAL = float(ENV("DEMO_SYNC_INTERVAL_SECONDS") or 5)
# 登录失败限流：同一用户名在窗口内失败这么多次后 429（默认值只是开发默认，不是基准或承诺）。
DEMO_LOGIN_MAX_FAILURES = int(ENV("DEMO_LOGIN_MAX_FAILURES") or 10)
DEMO_LOGIN_WINDOW = int(ENV("DEMO_LOGIN_WINDOW_SECONDS") or 300)

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


@app.get("/internal/origin/{scope}/{asset_id}/{file_path:path}")
def internal_origin(scope: str, asset_id: str, file_path: str,
                    x_origin_secret: str | None = Header(default=None)):
    """只给 Nginx 回源。对象存储不再匿名可读。"""
    if not x_origin_secret or not hmac.compare_digest(x_origin_secret.encode(), ORIGIN_SECRET.encode()):
        raise HTTPException(403, "origin denied")
    try:
        key = object_key(scope, asset_id, file_path)
    except ValueError:
        raise HTTPException(404, "bad path")
    try:
        obj = s3_internal.get_object(Bucket=HLS_BUCKET, Key=key)
    except Exception:
        raise HTTPException(404, "object not found")
    body = obj["Body"]

    def chunks():
        try:
            for chunk in body.iter_chunks(64 * 1024):
                yield chunk
        finally:
            body.close()

    ctype = obj.get("ContentType") or "application/octet-stream"
    # 对象大小已知就带上 Content-Length：没有它 Nginx 的 range 过滤会跳过，缓存命中后的 Range 请求只会回 200 全量。
    size = obj.get("ContentLength")
    headers = {"Content-Length": str(size)} if isinstance(size, int) and size >= 0 else None
    return StreamingResponse(chunks(), media_type=ctype, headers=headers)


# ---------------- 启动：建桶 + 播放计数回刷线程 ----------------
def check_secrets():
    """非 dev 下弱密钥直接让 startup 失败（uvicorn 退出）；dev 只警告。"""
    secret_guard.check_secrets(
        {"ORIGIN_SECRET": ORIGIN_SECRET, "HLS_SECRET": HLS_SECRET, "TOKEN_SECRET": TOKEN_SECRET.decode(),
         "DEMO_SESSION_SECRET": DEMO_SESSION_SECRET},
        VOD_ENV, MIN_SECRET_LENGTH, warn=lambda msg: log("weak_secrets", detail=msg))


@app.on_event("startup")
def startup():
    check_secrets()

    def ensure_buckets():
        existing = {b["Name"] for b in s3_internal.list_buckets()["Buckets"]}
        for b in (SOURCE_BUCKET, HLS_BUCKET):
            if b not in existing:
                s3_internal.create_bucket(Bucket=b)
        # 桶保持私有。边缘验完签名后，由 /internal/origin 用本服务的密钥回源。
        try:
            s3_internal.delete_bucket_policy(Bucket=HLS_BUCKET)
        except Exception:
            pass
    wait_for("minio", ensure_buckets)
    wait_for("mysql", lambda: db().close())
    wait_for("mysql", ensure_vod_link_table)
    wait_for("mysql", ensure_demo_schema)
    threading.Thread(target=flush_play_counts_loop, daemon=True).start()
    threading.Thread(target=core_sync_loop, daemon=True).start()
    log("api_started")


def ensure_vod_link_table():
    conn = db()
    with conn.cursor() as c:
        c.execute("CREATE TABLE IF NOT EXISTS episode_vod_link ("
                  "episode_id BIGINT PRIMARY KEY, vod_asset_id BIGINT NOT NULL, "
                  "vod_session_id BIGINT NOT NULL, "
                  "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)")
    conn.close()


# ---------------- 路线 C：Demo 自己的表 ----------------
DEMO_TABLES = (
    # 观众账号。coins 是演示用的金币余额，扣费和 user_unlock 在同一事务里。
    "CREATE TABLE IF NOT EXISTS demo_user ("
    "id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(32) NOT NULL, password_hash VARCHAR(200) NOT NULL, "
    "coins INT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
    "UNIQUE KEY uk_demo_username (username)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    # 金币流水：每次加减一行，balance 是变动后的余额，方便对账。
    "CREATE TABLE IF NOT EXISTS coin_ledger ("
    "id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, delta INT NOT NULL, balance INT NOT NULL, "
    "reason VARCHAR(32) NOT NULL, episode_id BIGINT NULL, order_no VARCHAR(64) NULL, "
    "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_ledger_user (user_id, id)) "
    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
    # 一集上传到 Core 的每个资产。PENDING：等 Core 转码；SWITCHED：这一集已切到它；FAILED / GONE：转码失败、资产没了。
    "CREATE TABLE IF NOT EXISTS episode_core_upload ("
    "core_asset_id BIGINT PRIMARY KEY, episode_id BIGINT NOT NULL, core_session_id BIGINT NOT NULL, "
    "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', source VARCHAR(16) NOT NULL DEFAULT 'upload', "
    "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, "
    "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
    "KEY idx_core_upload_status (status), KEY idx_core_upload_episode (episode_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
)
# episode 上追加的列：这一集当前播放的 Core 资产、时长（列表展示用，旧资产的时长仍从 video_asset 取）。
EPISODE_COLUMNS = {"core_asset_id": "BIGINT NULL", "duration_ms": "INT NULL"}


def ensure_demo_schema():
    conn = db()
    with conn.cursor() as c:
        for ddl in DEMO_TABLES:
            c.execute(ddl)
        c.execute("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'episode'")
        have = {row["COLUMN_NAME"].lower() for row in c.fetchall()}
        for name, ddl in EPISODE_COLUMNS.items():
            if name not in have:
                c.execute(f"ALTER TABLE episode ADD COLUMN {name} {ddl}")
        # 路线 C 之前经 vod-upload 接上的 Core 资产（episode_vod_link）补进对账表，由对账线程决定是否切换。
        c.execute("INSERT IGNORE INTO episode_core_upload(core_asset_id, episode_id, core_session_id, source) "
                  "SELECT vod_asset_id, episode_id, vod_session_id, 'link' FROM episode_vod_link")
    conn.close()


# ---------------- 路线 C：一集 → Core 资产的对账 ----------------
CORE_SYNC = Counter("demo_core_sync_total", "episode/core asset reconciliation", ["result"])


def pending_core_uploads(limit=50):
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT core_asset_id, episode_id FROM episode_core_upload WHERE status='PENDING' "
                  "ORDER BY core_asset_id LIMIT %s", (int(limit),))
        rows = c.fetchall()
    conn.close()
    return rows


def mark_core_upload(core_asset_id, status):
    conn = db()
    with conn.cursor() as c:
        c.execute("UPDATE episode_core_upload SET status=%s WHERE core_asset_id=%s AND status='PENDING'",
                  (status, core_asset_id))
    conn.close()


def switch_episode(episode_id, core_asset_id, duration_ms):
    """这一集改播 core_asset_id。只往更新的资产切（Core 资产 id 递增），同一集先后两次上传、先完成的那次不会覆盖后一次。
    返回是否切换。"""
    conn = db()
    with conn.cursor() as c:
        changed = c.execute(
            "UPDATE episode SET core_asset_id=%s, duration_ms=%s, status='READY' "
            "WHERE id=%s AND (core_asset_id IS NULL OR core_asset_id < %s)",
            (core_asset_id, duration_ms, episode_id, core_asset_id))
        c.execute("UPDATE episode_core_upload SET status=%s WHERE core_asset_id=%s",
                  ("SWITCHED" if changed else "SUPERSEDED", core_asset_id))
    conn.close()
    r.delete(f"ep:{episode_id}")
    return bool(changed)


def sync_core_upload(row):
    """问 Core 这个资产的状态：READY 就切换这一集；终态失败或资产不见了就不再等。返回结果名（测试和指标用）。"""
    status, payload = vod_client.asset(row["core_asset_id"])
    if status == 404:
        mark_core_upload(row["core_asset_id"], "GONE")
        result = "gone"
    elif status != 200:
        result = "core_error"
    elif payload.get("status") == "READY":
        result = "switched" if switch_episode(row["episode_id"], row["core_asset_id"], payload.get("duration_ms")) else "superseded"
    elif payload.get("status") in ("FAILED", "CANCELLED"):
        mark_core_upload(row["core_asset_id"], "FAILED")
        result = "failed"
    else:
        result = "waiting"
    CORE_SYNC.labels(result).inc()
    return result


def sync_core_uploads():
    return [sync_core_upload(row) for row in pending_core_uploads()]


def core_sync_loop():
    while True:
        time.sleep(DEMO_SYNC_INTERVAL)
        try:
            sync_core_uploads()
        except Exception as e:
            log("core_sync_error", err=str(e)[:200])


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


def _episode_rows(c, drama_id: int):
    c.execute(
        "SELECT e.id, e.ep_no, e.title, e.is_paid, e.price_coins, e.asset_id, e.core_asset_id, e.status, "
        "COALESCE(e.duration_ms, a.duration_ms) AS duration_ms "
        "FROM episode e LEFT JOIN video_asset a ON a.id = e.asset_id "
        "WHERE e.drama_id=%s ORDER BY e.ep_no", (drama_id,))
    rows = c.fetchall()
    for ep in rows:
        ep["is_paid"] = bool(ep["is_paid"])
        ep["price_coins"] = int(ep["price_coins"] or 0)
    return rows


@app.get("/api/dramas")
def list_dramas():
    """播放页用。只列已上架的剧，不改现有的建剧/播放接口。"""
    conn = db()
    with conn.cursor() as c:
        c.execute(
            "SELECT d.id, d.title, d.cover_key, d.total_episodes, d.status, "
            "(SELECT COUNT(*) FROM episode e WHERE e.drama_id=d.id AND e.status='READY') AS ready_episodes "
            "FROM drama d WHERE d.status='ONLINE' ORDER BY d.id DESC")
        rows = c.fetchall()
    conn.close()
    for row in rows:
        row["ready_episodes"] = int(row["ready_episodes"] or 0)
        row["total_episodes"] = int(row["total_episodes"] or 0)
    return {"dramas": rows}


@app.get("/api/dramas/{drama_id}")
def drama_detail(drama_id: int):
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT id, title, cover_key, total_episodes, status FROM drama WHERE id=%s", (drama_id,))
        drama = c.fetchone()
        episodes = _episode_rows(c, drama_id) if drama else []
    conn.close()
    if not drama:
        raise HTTPException(404, "drama not found")
    drama["total_episodes"] = int(drama["total_episodes"] or 0)
    return {"drama": drama, "episodes": episodes}


@app.get("/api/episodes/{episode_id}")
def episode_detail(episode_id: int):
    conn = db()
    with conn.cursor() as c:
        c.execute(
            "SELECT e.id, e.drama_id, e.ep_no, e.title, e.is_paid, e.price_coins, e.asset_id, e.core_asset_id, e.status, "
            "COALESCE(e.duration_ms, a.duration_ms) AS duration_ms "
            "FROM episode e LEFT JOIN video_asset a ON a.id = e.asset_id WHERE e.id=%s", (episode_id,))
        ep = c.fetchone()
        drama = None
        if ep:
            c.execute("SELECT id, title, cover_key, total_episodes, status FROM drama WHERE id=%s", (ep["drama_id"],))
            drama = c.fetchone()
    conn.close()
    if not ep:
        raise HTTPException(404, "episode not found")
    ep["is_paid"] = bool(ep["is_paid"])
    ep["price_coins"] = int(ep["price_coins"] or 0)
    return {"episode": ep, "drama": drama}


# ---------------- 上传：经视频云（路线 C） ----------------
def _core_error(status, payload, fallback):
    """Core 的 4xx 原样带回（409 = 源文件还没传完等），5xx / 连不上统一 502。"""
    raise HTTPException(status if 400 <= status < 500 else 502, payload.get("detail", fallback))


def _drama_title(drama_id):
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT title FROM drama WHERE id=%s", (drama_id,))
        row = c.fetchone()
    conn.close()
    return row["title"] if row else ""


def open_core_upload(episode_id, source="upload"):
    """为这一集在 Core 开一个 put 上传会话：付费集是加密资产；标题、标签写到 Core 资产上。记进 episode_core_upload。"""
    ep = get_episode(episode_id)
    title = f"{_drama_title(ep['drama_id'])} 第{ep['ep_no']}集 {ep.get('title') or ''}".strip()
    status, payload = vod_client.open_upload(encrypted=bool(ep["is_paid"]), title=title,
                                             tags=[f"drama-{ep['drama_id']}", f"episode-{episode_id}"], filename="source.mp4")
    if status != 200 or not payload.get("asset_id"):
        _core_error(status, payload, "vod upload failed")
    conn = db()
    with conn.cursor() as c:
        c.execute("INSERT INTO episode_core_upload(core_asset_id, episode_id, core_session_id, source) VALUES (%s,%s,%s,%s)",
                  (payload["asset_id"], episode_id, payload["session_id"], source))
    conn.close()
    log("core_upload_opened", episode_id=episode_id, core_asset_id=payload["asset_id"])
    return payload


def core_upload(core_asset_id):
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT core_asset_id, episode_id, core_session_id, status FROM episode_core_upload WHERE core_asset_id=%s",
                  (core_asset_id,))
        row = c.fetchone()
    conn.close()
    return row


def complete_core_upload(row, trace_id=None):
    status, payload = vod_client.complete_upload(row["core_session_id"])
    if status != 200:
        UPLOAD_DONE.labels("missing" if status == 409 else "error").inc()
        _core_error(status, payload, "vod complete failed")
    UPLOAD_DONE.labels("ok").inc()
    log("upload_complete", asset_id=row["core_asset_id"], job_id=payload.get("job_id"), trace_id=trace_id, via="vod-api")
    return {"asset_id": row["core_asset_id"], "job_id": payload.get("job_id"), "job_status": payload.get("job_status")}


@app.post("/api/episodes/{episode_id}/upload-url")
def upload_url(episode_id: int):
    """冻结接口，路线 C 起改为 Core 的上传会话：asset_id 是 Core 资产 id，put_url 直传到 Core 的源对象键
    raw/t{租户}/{资产}/source.mp4（不再是旧的 raw/{剧}/{集}/{资产}/），expires_in 是 Core 会话的有效期。"""
    payload = open_core_upload(episode_id)
    return {"asset_id": payload["asset_id"], "put_url": payload["put_url"], "expires_in": payload.get("expires_in")}


@app.post("/api/assets/{asset_id}/complete")
def upload_complete(asset_id: int, x_request_id: str | None = Header(default=None)):
    """冻结接口：对象不存在 409（Core 判断），重复调用返回同一个任务。旧链路的资产不再接受新的 complete。"""
    row = core_upload(asset_id)
    if not row:
        raise HTTPException(404, "asset not found")
    return complete_core_upload(row, x_request_id)


@app.get("/api/assets/{asset_id}")
def asset_status(asset_id: int):
    """冻结接口：asset.status、jobs[]、renditions[]。Core 资产从视频云读（顺便对一次账）；
    路线 C 之前的旧资产仍从旧表读。Core 不按档位记 renditions，返回空数组。"""
    row = core_upload(asset_id)
    if row:
        status, a = vod_client.asset(asset_id)
        if status != 200:
            _core_error(status, a, "vod asset lookup failed")
        if row["status"] == "PENDING":
            sync_core_upload(row)
        jstatus, jpayload = vod_client.jobs()
        jobs = [{"id": j["id"], "status": j["status"], "attempts": j.get("attempts"), "profile": j.get("profile")}
                for j in (jpayload.get("jobs") or []) if j.get("asset_id") == asset_id] if jstatus == 200 else []
        return {"asset": {"id": asset_id, "episode_id": row["episode_id"], "status": a.get("status"),
                          "duration_ms": a.get("duration_ms"), "width": a.get("width"), "height": a.get("height"),
                          "encrypted": 1 if a.get("encrypted") else 0, "hls_prefix": a.get("hls_prefix"), "via": "vod-api"},
                "jobs": jobs, "renditions": []}
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


# ---------------- 路线 C：观众账号 ----------------
class Credentials(BaseModel):
    username: str
    password: str


def _user_view(row):
    return {"id": row["id"], "username": row["username"], "coins": int(row["coins"])}


def _session_response(row):
    token, exp = accounts.issue_session(row["id"], DEMO_SESSION_SECRET.encode(), DEMO_SESSION_TTL)
    return {"token": token, "expires_at": exp, "user": _user_view(row)}


@app.post("/api/auth/register")
def register(body: Credentials):
    if not accounts.valid_username(body.username):
        raise HTTPException(400, "username: 3-32 letters, digits, '_', '.', '-'")
    if not accounts.valid_password(body.password):
        raise HTTPException(400, "password: 8-128 characters")
    conn = db()
    try:
        conn.begin()
        with conn.cursor() as c:
            if not c.execute("INSERT IGNORE INTO demo_user(username, password_hash, coins) VALUES (%s,%s,%s)",
                             (body.username, accounts.hash_password(body.password), DEMO_SIGNUP_COINS)):
                conn.rollback()
                raise HTTPException(409, "username already taken")
            uid = c.lastrowid
            if DEMO_SIGNUP_COINS:
                c.execute("INSERT INTO coin_ledger(user_id, delta, balance, reason) VALUES (%s,%s,%s,'signup')",
                          (uid, DEMO_SIGNUP_COINS, DEMO_SIGNUP_COINS))
            c.execute("SELECT id, username, coins FROM demo_user WHERE id=%s", (uid,))
            row = c.fetchone()
        conn.commit()
    finally:
        conn.close()
    log("demo_register", user_id=row["id"])
    return _session_response(row)


@app.post("/api/auth/login")
def login(body: Credentials):
    fail_key = f"login:fail:{(body.username or '')[:64].lower()}"
    if int(r.get(fail_key) or 0) >= DEMO_LOGIN_MAX_FAILURES:
        raise HTTPException(429, "too many failed logins, try again later", headers={"Retry-After": str(DEMO_LOGIN_WINDOW)})
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT id, username, coins, password_hash FROM demo_user WHERE username=%s", (body.username,))
        row = c.fetchone()
    conn.close()
    if not row or not accounts.verify_password(body.password or "", row["password_hash"]):
        pipe = r.pipeline()
        pipe.incr(fail_key)
        pipe.expire(fail_key, DEMO_LOGIN_WINDOW)
        pipe.execute()
        raise HTTPException(401, "wrong username or password")
    r.delete(fail_key)
    return _session_response(row)


def session_user(authorization):
    """Authorization: Bearer dsess1.… → user_id；没有或无效返回 None。"""
    return accounts.verify_session(accounts.bearer(authorization), DEMO_SESSION_SECRET.encode())


def require_user(authorization):
    uid = session_user(authorization)
    if uid is None:
        raise HTTPException(401, "login required")
    return uid


def viewer(authorization, user_id):
    """播放时的观众：有会话就用会话里的人，查询参数 user_id（旧接口）给了但对不上则 403；
    没有会话时，只有打开 DEMO_ALLOW_USER_ID_PARAM 才按 user_id 认人（旧行为），否则当匿名观众（只能看免费集）。"""
    uid = session_user(authorization)
    if uid is not None:
        if user_id is not None and int(user_id) != uid:
            raise HTTPException(403, "user_id does not match the logged-in viewer")
        return uid
    if DEMO_ALLOW_USER_ID_PARAM and user_id is not None:
        return int(user_id)
    return None


@app.get("/api/me")
def me(authorization: str | None = Header(default=None)):
    uid = require_user(authorization)
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT id, username, coins FROM demo_user WHERE id=%s", (uid,))
        row = c.fetchone()
        c.execute("SELECT episode_id FROM user_unlock WHERE user_id=%s ORDER BY id", (uid,))
        unlocked = [x["episode_id"] for x in c.fetchall()]
    conn.close()
    if not row:
        raise HTTPException(401, "login required")
    return {"user": _user_view(row), "unlocked": unlocked}


# ---------------- 付费 / 解锁 ----------------
class UnlockIn(BaseModel):
    episode_id: int
    order_no: str | None = None


UNLOCKS = Counter("demo_unlock_total", "unlock requests", ["result"])


@app.post("/api/users/{user_id}/unlock")
def unlock(user_id: int, body: UnlockIn, authorization: str | None = Header(default=None)):
    """路线 C：要本人的会话（没有 401、别人 403）。付费集在同一事务里写 user_unlock、扣 price_coins、记流水；
    已经解锁过不再扣（200、charged=0）；余额不够 402（code=insufficient_coins），什么都不写。免费集直接 200。"""
    uid = require_user(authorization)
    if uid != user_id:
        raise HTTPException(403, "can only unlock for yourself")
    ep = get_episode(body.episode_id)
    price = int(ep.get("price_coins") or 0) if ep["is_paid"] else 0
    conn = db()
    try:
        conn.begin()
        with conn.cursor() as c:
            c.execute("SELECT coins FROM demo_user WHERE id=%s FOR UPDATE", (uid,))
            user = c.fetchone()
            if not user:
                conn.rollback()
                raise HTTPException(401, "login required")
            if not ep["is_paid"]:
                conn.rollback()
                UNLOCKS.labels("free").inc()
                return {"ok": True, "charged": 0, "coins": int(user["coins"])}
            if not c.execute("INSERT IGNORE INTO user_unlock(user_id, episode_id, source, order_no) VALUES (%s,%s,'PURCHASE',%s)",
                             (uid, ep["id"], body.order_no)):
                conn.rollback()
                UNLOCKS.labels("already").inc()
                return {"ok": True, "charged": 0, "coins": int(user["coins"])}
            if price and not c.execute("UPDATE demo_user SET coins = coins - %s WHERE id=%s AND coins >= %s", (price, uid, price)):
                conn.rollback()
                UNLOCKS.labels("insufficient").inc()
                return JSONResponse(status_code=402, content={
                    "detail": f"need {price} coins, have {int(user['coins'])}", "code": "insufficient_coins",
                    "price": price, "coins": int(user["coins"])})
            balance = int(user["coins"]) - price
            c.execute("INSERT INTO coin_ledger(user_id, delta, balance, reason, episode_id, order_no) VALUES (%s,%s,%s,'unlock',%s,%s)",
                      (uid, -price, balance, ep["id"], body.order_no))
        conn.commit()
    finally:
        conn.close()
    r.sadd(f"unlock:{uid}", ep["id"])
    UNLOCKS.labels("charged").inc()
    log("demo_unlock", user_id=uid, episode_id=ep["id"], price=price)
    return {"ok": True, "charged": price, "coins": balance}


def can_play(user_id, ep: dict) -> bool:
    if not ep["is_paid"]:
        return True
    if user_id is None:  # 路线 C：匿名观众只能看免费集
        return False
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
        c.execute("SELECT id, drama_id, ep_no, title, is_paid, price_coins, asset_id, core_asset_id, status FROM episode WHERE id=%s",
                  (episode_id,))
        ep = c.fetchone()
    conn.close()
    if not ep:
        raise HTTPException(404, "episode not found")
    r.set(ck, json.dumps(ep), ex=60)
    return ep


def hls_dir(asset_id: int, expires: int) -> str:
    """nginx secure_link：md5( 过期时间 + "/" + 资产目录 + " " + 密钥 )，base64url。"""
    raw = f"{expires}/{asset_id} {HLS_SECRET}".encode()
    sig = base64.urlsafe_b64encode(hashlib.md5(raw).digest()).decode().rstrip("=")
    return f"/hls/{sig}/{expires}/{asset_id}"


def sign_hls_path(asset_id: int, expires: int) -> str:
    """签的是"整个资产目录"而不是单个文件，所以 m3u8 里的相对路径分片会自动带上同一个签名前缀。"""
    return f"{hls_dir(asset_id, expires)}/master.m3u8"


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


def play_info(user_id, ep: dict) -> dict:
    """旧链路（路线 C 之前的旧资产，还没迁移到 Core）的播放信息：旧签名地址 + 旧 token。"""
    exp = int(time.time()) + PLAY_URL_TTL
    PLAY_REQ.labels("legacy").inc()
    return {"episode_id": ep["id"], "asset_id": ep["asset_id"], "encrypted": bool(ep["is_paid"]),
            "master_url": sign_hls_path(ep["asset_id"], exp),
            "token": make_token(user_id or 0, ep["asset_id"], exp), "expires_at": exp, "via": "legacy"}


def core_play_info(user_id, ep: dict) -> dict:
    """路线 C：向视频云要 Core 播放 token（同时得到 hls.js 用的 master_url 和原生 HLS 用的 native_master_url）。
    encrypted 取 Core 资产的真实值。Core 409（还没就绪）原样返回，其余失败 502。"""
    viewer_id = f"u{user_id}" if user_id is not None else "anon"
    status, payload = vod_client.playback_token(ep["core_asset_id"], ttl_seconds=PLAY_URL_TTL, viewer_id=viewer_id)
    if status != 200 or not payload.get("token") or not payload.get("master_url"):
        raise HTTPException(409 if status == 409 else 502, payload.get("detail", "vod playback failed"))
    return {"episode_id": ep["id"], "asset_id": ep["core_asset_id"], "encrypted": bool(payload.get("encrypted")),
            "master_url": payload["master_url"], "native_master_url": payload.get("native_master_url"),
            "poster_url": payload.get("poster_url"), "token": payload["token"], "expires_at": payload.get("expires_at"),
            "via": "vod-api"}


def playable(ep: dict) -> bool:
    return ep["status"] == "READY" and bool(ep.get("core_asset_id") or ep.get("asset_id"))


def episode_play_info(user_id, ep: dict) -> dict:
    return core_play_info(user_id, ep) if ep.get("core_asset_id") else play_info(user_id, ep)


@app.post("/api/episodes/{episode_id}/vod-upload")
def vod_upload(episode_id: int):
    """播放页「上传到视频云」用。路线 C 起和 upload-url 是同一件事，返回 Core 的上传会话原样字段。"""
    return open_core_upload(episode_id)


@app.post("/api/episodes/{episode_id}/vod-complete")
def vod_complete(episode_id: int):
    """完成这一集最近一次还在等待的 Core 上传。"""
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT core_asset_id, episode_id, core_session_id, status FROM episode_core_upload "
                  "WHERE episode_id=%s AND status='PENDING' ORDER BY core_asset_id DESC LIMIT 1", (episode_id,))
        row = c.fetchone()
    conn.close()
    if not row:
        raise HTTPException(404, "upload first")
    return complete_core_upload(row)


@app.get("/api/episodes/{episode_id}/vod-play")
def vod_play(episode_id: int, user_id: int | None = None, authorization: str | None = Header(default=None)):
    """路线 C 起与 play 相同（保留给旧播放页）。"""
    return play(episode_id, user_id, authorization)


@app.get("/api/episodes/{episode_id}/play")
def play(episode_id: int, user_id: int | None = None, authorization: str | None = Header(default=None)):
    """冻结接口。路线 C：观众来自 Demo 会话（见 viewer）；付费集匿名 401、未解锁 402、未就绪 409。
    播放地址和 token 一律由视频云签（Core 资产）；还没迁移的旧资产暂时走旧链路（via=legacy）。"""
    uid = viewer(authorization, user_id)
    ep = get_episode(episode_id)
    if not playable(ep):
        PLAY_REQ.labels("not_ready").inc()
        raise HTTPException(409, "episode not ready (still transcoding?)")
    if not can_play(uid, ep):
        PLAY_REQ.labels("need_login" if uid is None else "need_unlock").inc()
        if uid is None:
            raise HTTPException(401, "login required for paid episodes")
        raise HTTPException(402, "paid episode, unlock first")
    info = episode_play_info(uid, ep)
    # 下一集：能播才给地址，客户端用来预加载 master + 首片
    conn = db()
    with conn.cursor() as c:
        c.execute("SELECT id FROM episode WHERE drama_id=%s AND ep_no=%s", (ep["drama_id"], ep["ep_no"] + 1))
        nxt = c.fetchone()
    conn.close()
    info["next"] = None
    if nxt:
        nep = get_episode(nxt["id"])
        if playable(nep):
            if can_play(uid, nep):
                try:
                    info["next"] = episode_play_info(uid, nep)
                except HTTPException:
                    info["next"] = None  # 下一集签不出来不影响本集
            else:
                info["next"] = {"episode_id": nep["id"], "locked": True}
    pipe = r.pipeline()
    pipe.incr(f"play:cnt:{episode_id}")
    pipe.zincrby(f"rank:hot:{time.strftime('%Y%m%d')}", 1, ep["drama_id"])
    pipe.execute()
    PLAY_REQ.labels("ok").inc()
    return info


@app.get("/keys/{asset_id}")
def get_key(asset_id: int, authorization: str | None = Header(default=None), token: str | None = None):
    """播放器拿 m3u8 里的 #EXT-X-KEY URI 来请求这里。没有合法 token 不给 key，分片偷走了也解不开。

    iPhone 自带 HLS 不能给 key 请求加 Authorization 头，所以也接受 ?token=。电脑上的 hls.js 仍走请求头，不把 token 放进分片 URL。
    """
    raw = (token or "").strip() or (authorization or "").removeprefix("Bearer ").strip()
    tok = verify_token(raw)
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


def _require_asset_token(asset_id: int, token: str) -> None:
    tok = verify_token((token or "").strip())
    if not tok or tok["a"] != asset_id:
        raise HTTPException(401, "invalid or expired play token")


def _s3_text(key: str) -> str:
    try:
        obj = s3_internal.get_object(Bucket=HLS_BUCKET, Key=key)
        body = obj["Body"]
        try:
            return body.read().decode("utf-8", "replace")
        finally:
            body.close()
    except Exception:
        raise HTTPException(404, "playlist not found")


def _rewrite_master(text: str, asset_id: int, token: str) -> str:
    """把子列表指到本服务，这样 iPhone 拉到的 media playlist 里才能带上 key token。"""
    q = quote(token, safe="")
    out = []
    for line in text.splitlines():
        s = line.strip()
        m = re.fullmatch(r"([A-Za-z0-9_-]+)/index\.m3u8", s)
        if m:
            out.append(f"/api/playback/{asset_id}/media.m3u8?rendition={m.group(1)}&token={q}")
        else:
            out.append(line)
    return "\n".join(out) + "\n"


def _rewrite_media(text: str, asset_id: int, rendition: str, token: str) -> str:
    prefix = hls_dir(asset_id, int(time.time()) + PLAY_URL_TTL)
    q = quote(token, safe="")
    out = []
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("#EXT-X-KEY"):
            out.append(re.sub(r'URI="([^"]*)"', lambda m: f'URI="{m.group(1).split("?")[0]}?token={q}"', line))
        elif s and not s.startswith("#"):
            name = s.split("?", 1)[0].split("/")[-1]
            if re.fullmatch(r"[A-Za-z0-9_.-]+", name):
                out.append(f"{prefix}/{rendition}/{name}")
            else:
                out.append(line)
        else:
            out.append(line)
    return "\n".join(out) + "\n"


def _m3u8(text: str) -> Response:
    return Response(text, media_type="application/vnd.apple.mpegurl", headers={"Cache-Control": "no-store"})


@app.get("/api/playback/{asset_id}/master.m3u8")
def playback_master(asset_id: int, token: str = ""):
    """Safari / iPhone 不能给分片和 key 加请求头。这里现生成一份播放列表：分片仍走原来的签名 CDN 地址，key 带 ?token=。
    电脑和 Android 继续直接用 play 接口返回的 master_url，不经过这里，CDN 缓存行为不变。"""
    _require_asset_token(asset_id, token)
    text = _s3_text(f"assets/{asset_id}/master.m3u8")
    return _m3u8(_rewrite_master(text, asset_id, token))


@app.get("/api/playback/{asset_id}/media.m3u8")
def playback_media(asset_id: int, rendition: str, token: str = ""):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", rendition or ""):
        raise HTTPException(404, "playlist not found")
    _require_asset_token(asset_id, token)
    text = _s3_text(f"assets/{asset_id}/{rendition}/index.m3u8")
    return _m3u8(_rewrite_media(text, asset_id, rendition, token))


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
