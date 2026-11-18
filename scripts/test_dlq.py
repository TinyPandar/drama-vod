#!/usr/bin/env python3
"""不需要 Docker。scripts/dlq.py：list 只看不丢；replay 只接受 FAILED 的 Core 任务、以库为准写 outbox。
真实 MySQL 上的重放见 scripts/test_fencing_mysql.py。"""
import json, os, sys, types, unittest

sys.path.insert(0, os.path.dirname(__file__))
import dlq  # noqa: E402


def message(job_id, asset_id, trace_id):
    return {"schema_version": 1, "job_id": job_id, "asset_id": asset_id, "trace_id": trace_id}


class FakeChannel:
    def __init__(self, bodies):
        self.queue = [json.dumps(b).encode() if isinstance(b, dict) else b for b in bodies]
        self.out, self.acked, self.nacked, self.tag = {}, [], [], 0

    def basic_get(self, queue, auto_ack=False):
        if not self.queue:
            return None, None, None
        self.tag += 1
        body = self.queue.pop(0)
        self.out[self.tag] = body
        return types.SimpleNamespace(delivery_tag=self.tag), None, body

    def basic_ack(self, tag):
        self.acked.append(self.out.pop(tag))

    def basic_nack(self, tag, requeue=True):
        assert requeue
        self.nacked.append(tag)
        self.queue.append(self.out.pop(tag))


class FakeCursor:
    def __init__(self, rows):
        self.rows, self.calls, self.lastrowid = list(rows), [], 42

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def execute(self, sql, params=None):
        self.calls.append((sql, params))
        return 1

    def fetchone(self):
        return self.rows.pop(0) if self.rows else None


class FakeConn:
    def __init__(self, rows):
        self.cur, self.events = FakeCursor(rows), []

    def cursor(self):
        return self.cur

    def begin(self):
        self.events.append("begin")

    def commit(self):
        self.events.append("commit")

    def rollback(self):
        self.events.append("rollback")


CORE_7 = {"schema_version": 1, "job_id": 7, "asset_id": 3, "trace_id": None, "origin": "core"}
LEGACY_7 = {"schema_version": 1, "job_id": 7, "asset_id": 3, "trace_id": None}


class DlqTest(unittest.TestCase):
    def test_list_puts_every_message_back(self):
        ch = FakeChannel([CORE_7, LEGACY_7, b"not json"])
        conn = FakeConn([{"status": "FAILED", "error_code": "NO_VIDEO_STREAM"}, {"status": "FAILED"}])
        rows = dlq.list_dlq(ch, conn, "transcode.dlq", 10)
        self.assertEqual(len(rows), 3)
        self.assertEqual(ch.acked, [])
        self.assertEqual(len(ch.queue), 3)
        self.assertEqual(rows[0]["origin"], "core")
        self.assertEqual(rows[0]["db"]["error_code"], "NO_VIDEO_STREAM")
        self.assertIn("FROM vod_transcode_job", conn.cur.calls[0][0])
        self.assertIn("FROM transcode_job", conn.cur.calls[1][0])
        self.assertIsNone(rows[2]["db"])

    def test_replay_refuses_non_failed_and_writes_nothing(self):
        for status in ("PENDING", "RUNNING", "RETRYING", "SUCCEEDED", "CANCELLED"):
            conn = FakeConn([{"id": 7, "tenant_id": 1, "asset_id": 3, "status": status}])
            with self.assertRaises(dlq.ReplayRefused):
                dlq.replay_job(conn, 7, message)
            self.assertEqual(conn.events, ["begin", "rollback"])
            self.assertEqual(len(conn.cur.calls), 1)  # 只有 SELECT ... FOR UPDATE

    def test_replay_refuses_missing_job(self):
        conn = FakeConn([])
        with self.assertRaises(dlq.ReplayRefused):
            dlq.replay_job(conn, 99, message)

    def test_replay_refuses_deleted_asset(self):
        conn = FakeConn([{"id": 7, "tenant_id": 1, "asset_id": 3, "status": "FAILED"}, {"status": "DELETED"}])
        with self.assertRaises(dlq.ReplayRefused):
            dlq.replay_job(conn, 7, message)
        self.assertEqual(conn.events, ["begin", "rollback"])
        self.assertFalse(any(s.startswith("UPDATE") or s.startswith("INSERT") for s, _ in conn.cur.calls))

    def test_replay_resets_job_and_writes_outbox(self):
        conn = FakeConn([{"id": 7, "tenant_id": 1, "asset_id": 3, "status": "FAILED"}, {"status": "FAILED"}])
        self.assertEqual(dlq.replay_job(conn, 7, message), 42)
        self.assertEqual(conn.events, ["begin", "commit"])
        sqls = [s for s, _ in conn.cur.calls]
        self.assertIn("FOR UPDATE", sqls[0])
        self.assertIn("FROM vod_media_asset WHERE id=%s FOR UPDATE", sqls[1])
        self.assertIn("status='PENDING', attempts=0, error_code=NULL", sqls[2])
        self.assertIn("claim_version=claim_version+1", sqls[2])
        self.assertIn("UPDATE vod_media_asset SET status='UPLOADED'", sqls[3])
        self.assertIn("INSERT INTO vod_outbox", sqls[4])
        payload = json.loads(conn.cur.calls[4][1][2])
        self.assertEqual((payload["job_id"], payload["asset_id"], payload["origin"]), (7, 3, "core"))
        self.assertEqual(payload["schema_version"], 1)

    def test_drop_only_removes_matching_core_messages(self):
        ch = FakeChannel([CORE_7, LEGACY_7, dict(CORE_7, job_id=8), CORE_7])
        self.assertEqual(dlq.drop_from_dlq(ch, "transcode.dlq", 7, 100), 2)
        self.assertEqual(len(ch.queue), 2)
        remaining = [json.loads(b) for b in ch.queue]
        self.assertIn(LEGACY_7, remaining)  # 同 id 的旧 Demo 消息不动
        self.assertIn(dict(CORE_7, job_id=8), remaining)


if __name__ == "__main__":
    unittest.main(verbosity=2)
