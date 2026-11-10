"""任务与资产状态更新的 claim_version 围栏。

每次 claim 都把 claim_version +1；之后这个进程对任务和资产的所有写入都带上自己拿到的版本号，
旧 worker（租约过期被接手、或任务被取消）写不进去。

MySQL 的 affected rows 语义：PyMySQL 默认不带 CLIENT_FOUND_ROWS，只统计「实际变化」的行；
值本来就相同时返回 0。这里的条件 UPDATE 尽量让「匹配」必然伴随「变化」（claim_version+1、状态变化），
做不到的（资产状态、续租）在返回 0 时再 SELECT 确认一次。scripts/test_fencing_mysql.py 在真实 MySQL 上核对。
"""
import output_prefix
from job_route import job_table, updates_episode

ASSET_TABLE = {"core": "vod_media_asset", "legacy": "video_asset"}


def update_asset_status(c, origin, asset_id, job_id, version, status):
    """资产状态 + 任务围栏一起更新。返回影响行数；1 表示围栏仍属于自己。"""
    asset = ASSET_TABLE[origin]
    n = c.execute(
        f"UPDATE {asset} a JOIN {job_table(origin)} j ON j.asset_id=a.id "
        "SET a.status=%s WHERE a.id=%s AND j.id=%s AND j.claim_version=%s",
        (status, asset_id, job_id, version))
    if n == 0:
        # MySQL 只统计实际变化的行：状态本来就是目标值时也是 0。再确认一次围栏，
        # 避免把"重试时资产已经是 TRANSCODING"误判成围栏丢失。
        return 1 if _fence_held(c, origin, asset_id, job_id, version) else 0
    return n


def mark_transcoding(conn, c, origin, asset_id, job_id, version):
    """领取后把资产改成 TRANSCODING。显式事务，先 SELECT ... FOR UPDATE 锁任务行、确认围栏，再改资产。

    加锁顺序与 vod-api 的取消事务（任务 → 资产）、成功/失败事务（先写任务行）一致；
    以前那条自动提交的 UPDATE 资产 JOIN 任务可能先锁资产再锁任务，与取消交错时会死锁。
    返回 1 表示围栏仍属于自己，0 表示已丢（被取消或被别人重新领取）。
    """
    asset = ASSET_TABLE[origin]
    conn.begin()
    try:
        c.execute(f"SELECT claim_version, status FROM {job_table(origin)} WHERE id=%s FOR UPDATE", (job_id,))
        row = c.fetchone()
        if not row or row["claim_version"] != version or row["status"] != "RUNNING":
            conn.rollback()
            return 0
        c.execute(f"UPDATE {asset} SET status='TRANSCODING' WHERE id=%s", (asset_id,))
        conn.commit()
        return 1
    except Exception:
        conn.rollback()
        raise


def _fence_held(c, origin, asset_id, job_id, version):
    c.execute(
        f"SELECT 1 FROM {ASSET_TABLE[origin]} a JOIN {job_table(origin)} j ON j.asset_id=a.id "
        "WHERE a.id=%s AND j.id=%s AND j.claim_version=%s LIMIT 1",
        (asset_id, job_id, version))
    return c.fetchone() is not None


def apply_failure(c, origin, asset_id, job_id, version, job_changed, final):
    """失败收尾：任务 UPDATE 没抢到（围栏已丢）就什么都别写，返回 False。

    返回 True 表示围栏仍属于自己，调用方可以照常投递 retry/DLQ。
    """
    if job_changed != 1:
        return False
    if final:
        update_asset_status(c, origin, asset_id, job_id, version, "FAILED")
    return True


def _core_touch(origin):
    """Core 表的 updated_at 没有 ON UPDATE，状态变化时显式维护；旧表自带 ON UPDATE。"""
    return ", updated_at=CURRENT_TIMESTAMP" if origin == "core" else ""


def claim(c, origin, job_id, worker_id, lease_seconds):
    """PENDING/RETRYING 可抢；RUNNING 只在租约过期后可抢。

    消息被 RabbitMQ 重投（连接断开、broker 重启）不代表原进程已死，所以重投不再抢占租约内的任务。
    claim_version 每次 +1，所以匹配到的行一定有变化，PyMySQL / Connector/J 都返回 1。
    """
    extra = ", next_attempt_at=NULL" + _core_touch(origin) if origin == "core" else ""
    n = c.execute(
        f"UPDATE {job_table(origin)} SET status='RUNNING', attempts=attempts+1, worker_id=%s, "
        f"claim_version=claim_version+1, locked_until=NOW() + INTERVAL %s SECOND{extra} "
        "WHERE id=%s AND (status IN ('PENDING','RETRYING') OR (status='RUNNING' AND locked_until < NOW()))",
        (worker_id, int(lease_seconds), job_id))
    return n == 1


def fence_held(c, origin, job_id, version):
    """任务仍是 RUNNING 且 claim_version 没变。取消会把状态改成 CANCELLED 并 +1 版本。"""
    c.execute(f"SELECT 1 FROM {job_table(origin)} WHERE id=%s AND claim_version=%s AND status='RUNNING'",
              (job_id, version))
    return c.fetchone() is not None


def renew_lease(c, origin, job_id, version, lease_seconds):
    """续租。同一秒内连续续租时 locked_until 不变，PyMySQL 返回 0，所以 0 时再查一次围栏。"""
    n = c.execute(
        f"UPDATE {job_table(origin)} SET locked_until=NOW() + INTERVAL %s SECOND "
        "WHERE id=%s AND claim_version=%s AND status='RUNNING'",
        (int(lease_seconds), job_id, version))
    return n == 1 or (n == 0 and fence_held(c, origin, job_id, version))


def commit_success(c, origin, job_id, version, asset_id, prefix, dur_ms, width, height, renditions=(),
                   episode_id=None):
    """成功事务的写入部分，调用方负责 begin/commit/rollback。

    先写带 claim_version 的任务 SUCCEEDED：影响 1 行才继续写资产 READY（Core 同时把 vod_output_prefix 切到本前缀）；这条 UPDATE 同时锁住任务行，
    并发的取消或新 claim 要等本事务结束，不会出现「任务被取消、资产却 READY」。
    返回 False 表示围栏已丢，调用方 rollback 后丢弃。
    """
    table = job_table(origin)
    extra = ", error_code=NULL, next_attempt_at=NULL" + _core_touch(origin) if origin == "core" else ""
    changed = c.execute(
        f"UPDATE {table} SET status='SUCCEEDED', locked_until=NULL, last_error=NULL{extra} "
        "WHERE id=%s AND claim_version=%s AND status='RUNNING'", (job_id, version))
    if changed != 1:
        return False
    if updates_episode(origin):
        for name, rw, rh, kbps in renditions:
            c.execute("INSERT INTO asset_rendition(asset_id, name, width, height, video_kbps, playlist_key) "
                      "VALUES(%s,%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE video_kbps=VALUES(video_kbps)",
                      (asset_id, name, rw, rh, kbps, f"{prefix}{name}/index.m3u8"))
        c.execute("UPDATE video_asset SET status='READY', hls_prefix=%s, duration_ms=%s, width=%s, height=%s "
                  "WHERE id=%s", (prefix, dur_ms, width, height, asset_id))
        c.execute("UPDATE episode SET asset_id=%s, status='READY' WHERE id=%s", (asset_id, episode_id))
    else:
        c.execute("UPDATE vod_media_asset SET status='READY', hls_prefix=%s, duration_ms=%s, width=%s, height=%s "
                  "WHERE id=%s", (prefix, dur_ms, width, height, asset_id))
        # 原子切换点就是这个事务：hls_prefix 与前缀状态一起提交，同资产别的前缀交给清理线程
        output_prefix.activate(c, asset_id, prefix)
    return True


def record_failure(c, origin, job_id, version, final, error, error_code=None, delay_ms=None):
    """失败写回任务，返回影响行数（RUNNING → RETRYING/FAILED 一定有变化）。

    Core 额外写 error_code，RETRYING 时写 next_attempt_at 供 sweeper 兜底；旧表只有 last_error。
    """
    status = "FAILED" if final else "RETRYING"
    if origin != "core":
        return c.execute(
            f"UPDATE {job_table(origin)} SET status=%s, locked_until=NULL, last_error=%s "
            "WHERE id=%s AND claim_version=%s AND status='RUNNING'",
            (status, error, job_id, version))
    if final:
        nxt, args = "NULL", ()
    else:
        nxt, args = "NOW() + INTERVAL %s SECOND", (max(1, -(-int(delay_ms or 0) // 1000)),)
    return c.execute(
        f"UPDATE vod_transcode_job SET status=%s, locked_until=NULL, last_error=%s, error_code=%s, "
        f"next_attempt_at={nxt}, updated_at=CURRENT_TIMESTAMP "
        "WHERE id=%s AND claim_version=%s AND status='RUNNING'",
        (status, error, error_code) + args + (job_id, version))


def due_jobs(c, origin, pending_age_seconds):
    """sweeper 要重新投递的任务：PENDING 太久没人领、RUNNING 租约过期；
    Core 另外兜底 RETRYING 且过了 next_attempt_at（延迟重试消息丢了）。CANCELLED 和终态不选。"""
    age = int(pending_age_seconds)
    if origin == "core":
        c.execute("SELECT id, asset_id FROM vod_transcode_job WHERE "
                  "(status='PENDING' AND updated_at < NOW() - INTERVAL %s SECOND) OR "
                  "(status='RUNNING' AND locked_until < NOW()) OR "
                  "(status='RETRYING' AND COALESCE(next_attempt_at, updated_at) < NOW() - INTERVAL %s SECOND)",
                  (age, age))
    else:
        c.execute("SELECT id, asset_id, trace_id FROM transcode_job WHERE "
                  "(status='PENDING' AND updated_at < NOW() - INTERVAL %s SECOND) OR "
                  "(status='RUNNING' AND locked_until < NOW())", (age,))
    return c.fetchall()
