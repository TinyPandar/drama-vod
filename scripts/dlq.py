#!/usr/bin/env python3
"""transcode.dlq 运维脚本。运维工具，不是租户 API（管理端鉴权属于 Phase 5）。

  dlq.py list [--limit N]   只看不丢：列出死信消息，以及对应任务在库里的状态 / error_code
  dlq.py replay --job-id N  只接受库里 FAILED 的 Core 任务：一个事务里把任务重置为 PENDING
                            （清 error_code/last_error、attempts 归零、claim_version+1）、资产改回 UPLOADED、
                            插入新的 vod_outbox 行，由 vod-api 的 OutboxPublisher 发布；提交后再从 DLQ 移除该任务的消息。
                            以数据库为准，不把 DLQ 里的原消息转发回主队列。

连接配置沿用 worker 的环境变量（MYSQL_HOST/MYSQL_USER/MYSQL_PASSWORD/MYSQL_DB、AMQP_URL）。
compose 下在 worker 镜像里跑（镜像里有 /app/common.py 和依赖）：
  docker compose run --rm --no-deps -v "$PWD/scripts:/scripts:ro" worker python /scripts/dlq.py list
"""
import argparse, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path[:0] = [os.path.join(HERE, "..", "worker"), "/app"]

from job_route import origin_of  # noqa: E402


class ReplayRefused(Exception):
    pass


def parse(body):
    try:
        msg = json.loads(body)
    except (TypeError, ValueError):
        return None
    return msg if isinstance(msg, dict) else None


def is_core_message_for(msg, job_id):
    """DLQ 里属于这个 Core 任务的消息。旧消息没有 origin，按旧链路算，不会误删。"""
    return bool(msg) and origin_of(msg) == "core" and str(msg.get("job_id")) == str(job_id)


def peek(ch, queue, limit):
    """basic_get 但不 ack；调用方决定 ack（删除）还是 nack 放回。"""
    out = []
    for _ in range(limit):
        method, _props, body = ch.basic_get(queue, auto_ack=False)
        if method is None:
            break
        out.append((method.delivery_tag, body))
    return out


def job_state(c, msg):
    if not msg or msg.get("job_id") is None:
        return None
    if origin_of(msg) == "core":
        c.execute("SELECT status, error_code, attempts, max_attempts, claim_version, last_error "
                  "FROM vod_transcode_job WHERE id=%s", (msg["job_id"],))
    else:
        c.execute("SELECT status, attempts, max_attempts, claim_version, last_error FROM transcode_job WHERE id=%s",
                  (msg["job_id"],))
    return c.fetchone()


def list_dlq(ch, conn, queue, limit):
    msgs = peek(ch, queue, limit)
    rows = []
    try:
        with conn.cursor() as c:
            for _tag, body in msgs:
                msg = parse(body)
                rows.append({"message": msg if msg is not None else body[:200].decode("utf-8", "replace"),
                             "origin": origin_of(msg) if msg else None,
                             "db": job_state(c, msg)})
    finally:
        for tag, _ in msgs:  # 只看不丢：全部放回
            ch.basic_nack(tag, requeue=True)
    return rows


def replay_job(conn, job_id, message_fn):
    """数据库为准的重放。返回新 outbox 行 id；任务不存在或不是 FAILED 时抛 ReplayRefused，不改任何数据。"""
    conn.begin()
    try:
        with conn.cursor() as c:
            c.execute("SELECT id, tenant_id, asset_id, status FROM vod_transcode_job WHERE id=%s FOR UPDATE",
                      (job_id,))
            job = c.fetchone()
            if not job:
                raise ReplayRefused(f"core job {job_id} not found")
            if job["status"] != "FAILED":
                raise ReplayRefused(f"core job {job_id} is {job['status']}; only FAILED can be replayed")
            c.execute("UPDATE vod_transcode_job SET status='PENDING', attempts=0, error_code=NULL, last_error=NULL, "
                      "locked_until=NULL, worker_id=NULL, next_attempt_at=NULL, claim_version=claim_version+1, "
                      "updated_at=CURRENT_TIMESTAMP WHERE id=%s AND status='FAILED'", (job_id,))
            c.execute("UPDATE vod_media_asset SET status='UPLOADED' WHERE id=%s AND status <> 'READY'",
                      (job["asset_id"],))
            body = message_fn(job["id"], job["asset_id"], None)
            body["origin"] = "core"
            c.execute("INSERT INTO vod_outbox(tenant_id, event_type, payload, status) VALUES (%s,%s,%s,'PENDING')",
                      (job["tenant_id"], "transcode.requested", json.dumps(body)))
            outbox_id = c.lastrowid
        conn.commit()
        return outbox_id
    except Exception:
        conn.rollback()
        raise


def drop_from_dlq(ch, queue, job_id, limit):
    """移除该 Core 任务的死信消息，其他消息原样放回。返回移除条数。"""
    removed = 0
    for tag, body in peek(ch, queue, limit):
        if is_core_message_for(parse(body), job_id):
            ch.basic_ack(tag)
            removed += 1
        else:
            ch.basic_nack(tag, requeue=True)
    return removed


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    ls = sub.add_parser("list", help="list DLQ messages without removing them")
    ls.add_argument("--limit", type=int, default=100)
    rp = sub.add_parser("replay", help="reset a FAILED core job and enqueue it through the outbox")
    rp.add_argument("--job-id", type=int, required=True)
    rp.add_argument("--limit", type=int, default=1000, help="max DLQ messages to scan when removing")
    args = ap.parse_args(argv)

    from common import Q_DLQ, db, mq_channel, transcode_message

    conn = db()
    try:
        if args.cmd == "list":
            mconn, ch = mq_channel()
            try:
                rows = list_dlq(ch, conn, Q_DLQ, args.limit)
            finally:
                mconn.close()
            for row in rows:
                print(json.dumps(row, ensure_ascii=False, default=str))
            print(f"# {len(rows)} message(s) shown, none removed")
            return 0
        try:
            outbox_id = replay_job(conn, args.job_id, transcode_message)
        except ReplayRefused as e:
            print(f"REFUSED {e}", file=sys.stderr)
            return 2
        print(f"replayed job {args.job_id}: status=PENDING, outbox_id={outbox_id}")
        try:
            mconn, ch = mq_channel()
            try:
                removed = drop_from_dlq(ch, Q_DLQ, args.job_id, args.limit)
            finally:
                mconn.close()
            print(f"removed {removed} DLQ message(s) for job {args.job_id}")
        except Exception as e:  # 库里已经重置；DLQ 里残留的旧消息无害，list 会显示任务已不是 FAILED
            print(f"WARN could not clean DLQ: {e}", file=sys.stderr)
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
