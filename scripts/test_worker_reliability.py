#!/usr/bin/env python3
"""不需要 Docker/MySQL。Phase 3 Worker 可靠性单测：重投不抢占、上传前复核围栏、取消、错误分类与退避。

process() 的数据库/对象存储依赖都换成假的；SQL 本身在 scripts/test_fencing_mysql.py 里用真实 MySQL 核对。
需要 Python 3.10+（worker/common.py 用了 `str | None`）；缺 worker/requirements.txt 里的包时用空壳模块代替。
"""
import os, sys, threading, time, types, unittest

if sys.version_info < (3, 10):
    raise SystemExit("test_worker_reliability.py needs Python 3.10+ (worker/common.py uses `str | None`)")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "worker"))


def _stub_missing_deps():
    """只在没装依赖时塞空壳，装了就用真的。worker 模块导入时只会创建客户端，不会连网络。"""
    def need(name):
        try:
            __import__(name)
            return False
        except ImportError:
            return True

    if need("boto3"):
        sys.modules["boto3"] = types.SimpleNamespace(client=lambda *a, **k: object())
        sys.modules["botocore"] = types.ModuleType("botocore")
        sys.modules["botocore.config"] = types.SimpleNamespace(Config=lambda **k: None)
    if need("pika"):
        exc = types.SimpleNamespace(AMQPError=Exception)
        sys.modules["pika"] = types.SimpleNamespace(exceptions=exc, BasicProperties=lambda **k: k)
    if need("pymysql"):
        sys.modules["pymysql"] = types.SimpleNamespace(cursors=types.SimpleNamespace(DictCursor=None))
    if need("redis"):
        sys.modules["redis"] = types.SimpleNamespace(Redis=types.SimpleNamespace(from_url=lambda *a, **k: None))
    if need("prometheus_client"):
        class Metric:
            def __init__(self, *a, **k):
                self.value = 0

            def labels(self, *a, **k):
                return self

            def inc(self, n=1):
                self.value += n

            def dec(self, n=1):
                self.value -= n

            def observe(self, v):
                pass

            def set_to_current_time(self):
                self.value = time.time()

        sys.modules["prometheus_client"] = types.SimpleNamespace(
            Counter=Metric, Gauge=Metric, Histogram=Metric, start_http_server=lambda *a: None)


_stub_missing_deps()
import fencing  # noqa: E402
import retry_policy as rp  # noqa: E402
import worker  # noqa: E402


class FakeCursor:
    """记录 SQL；affected 是 UPDATE 返回值（可给列表按次返回），rows 依次给 fetchone。"""

    def __init__(self, affected=1, rows=None):
        self.affected, self.rows, self.calls = affected, list(rows or []), []

    def execute(self, sql, params=None):
        self.calls.append((sql, params))
        if isinstance(self.affected, list):
            return self.affected.pop(0)
        return self.affected

    def fetchone(self):
        return self.rows.pop(0) if self.rows else None


class FakeConn:
    def __init__(self, cursor):
        self.cur, self.events = cursor, []

    def cursor(self):
        return self.cur

    def begin(self):
        self.events.append("begin")

    def commit(self):
        self.events.append("commit")

    def rollback(self):
        self.events.append("rollback")

    def close(self):
        pass


class FakeS3:
    """记录上传；list_objects_v2 按已上传对象回答，mutate 可以模拟远端缺文件/大小不符。"""

    def __init__(self, mutate=None):
        self.uploaded, self.objects, self.mutate = [], {}, mutate

    def upload_file(self, local, bucket, key, ExtraArgs=None):
        self.uploaded.append(key)
        self.objects[key] = os.path.getsize(local)

    def list_objects_v2(self, Bucket, Prefix, ContinuationToken=None, MaxKeys=None):
        objects = {k: v for k, v in self.objects.items() if k.startswith(Prefix)}
        if self.mutate:
            objects = self.mutate(dict(objects))
        return {"Contents": [{"Key": k, "Size": v} for k, v in sorted(objects.items())], "IsTruncated": False}


def metric_value(m):
    """真 prometheus_client 和空壳都能读。"""
    if hasattr(m, "_value"):
        return m._value.get()
    return m.value


class ClaimSqlTest(unittest.TestCase):
    def test_running_is_only_claimable_after_lease_expiry(self):
        c = FakeCursor()
        self.assertTrue(fencing.claim(c, "core", 3, "w1", 30))
        sql, params = c.calls[0]
        self.assertIn("(status='RUNNING' AND locked_until < NOW())", sql)
        self.assertNotIn("OR status='RUNNING')", sql)
        self.assertIn("claim_version=claim_version+1", sql)
        self.assertIn("INTERVAL %s SECOND", sql)
        self.assertIn("next_attempt_at=NULL", sql)
        self.assertEqual(params, ("w1", 30, 3))

    def test_legacy_claim_does_not_touch_core_only_columns(self):
        c = FakeCursor()
        fencing.claim(c, "legacy", 3, "w1", 30)
        sql = c.calls[0][0]
        self.assertIn("UPDATE transcode_job", sql)
        self.assertNotIn("next_attempt_at", sql)
        self.assertNotIn("error_code", sql)

    def test_process_has_no_steal_path(self):
        import inspect
        self.assertNotIn("steal", inspect.signature(worker.process).parameters)
        self.assertNotIn("steal", inspect.signature(fencing.claim).parameters)

    def test_redelivery_while_lease_valid_requeues(self):
        # 重投 + 租约有效：claim 的 UPDATE 影响 0 行，回查到 RUNNING -> requeue，不抢
        cur = FakeCursor(affected=0, rows=[{"status": "RUNNING", "locked_until": "future"}])
        with patched(db=lambda: FakeConn(cur)):
            self.assertEqual(worker.process(11, "t", "core"), "requeue")
        self.assertEqual(len([s for s, _ in cur.calls if s.startswith("UPDATE")]), 1)

    def test_terminal_or_cancelled_job_is_dropped(self):
        for status in ("SUCCEEDED", "FAILED", "CANCELLED"):
            cur = FakeCursor(affected=0, rows=[{"status": status, "locked_until": None}])
            with patched(db=lambda: FakeConn(cur)):
                self.assertEqual(worker.process(12, "t", "core"), "drop", status)

    def test_requeue_uses_configured_delay(self):
        sent = []
        ch = types.SimpleNamespace(basic_ack=lambda tag: sent.append(("ack", tag)))
        with patched(publish=lambda ch_, q, body, delay_ms=None: sent.append((q, delay_ms))):
            worker.finish(ch, types.SimpleNamespace(delivery_tag=5), {"job_id": 1}, "requeue")
            worker.finish(ch, types.SimpleNamespace(delivery_tag=6), {"job_id": 1}, "dlq")
            worker.finish(ch, types.SimpleNamespace(delivery_tag=7), {"job_id": 1}, "drop")
            worker.finish(ch, types.SimpleNamespace(delivery_tag=8), {"job_id": 1}, ("retry", 1234))
        self.assertEqual(sent, [(worker.Q_RETRY, worker.REQUEUE_DELAY_MS), ("ack", 5),
                                (worker.Q_DLQ, None), ("ack", 6), ("ack", 7),
                                (worker.Q_RETRY, 1234), ("ack", 8)])


class FencingSqlTest(unittest.TestCase):
    def test_success_writes_job_first_and_stops_when_fence_lost(self):
        c = FakeCursor(affected=0)
        self.assertFalse(fencing.commit_success(c, "core", 3, 5, 7, "assets/v1/7/", 1000, 720, 1280))
        self.assertEqual(len(c.calls), 1)  # 只有任务 UPDATE，资产 READY 没写
        sql, params = c.calls[0]
        self.assertIn("status='SUCCEEDED'", sql)
        self.assertIn("claim_version=%s AND status='RUNNING'", sql)
        self.assertEqual(params, (3, 5))

    def test_success_with_fence_writes_ready_after_job(self):
        c = FakeCursor(affected=1)
        self.assertTrue(fencing.commit_success(c, "core", 3, 5, 7, "assets/v1/7/", 1000, 720, 1280))
        self.assertIn("SUCCEEDED", c.calls[0][0])
        self.assertIn("error_code=NULL", c.calls[0][0])
        self.assertIn("UPDATE vod_media_asset SET status='READY'", c.calls[1][0])

    def test_core_success_switches_prefix_in_same_transaction(self):
        c = FakeCursor(affected=1)
        self.assertTrue(fencing.commit_success(c, "core", 3, 5, 7, "assets/v1/7/3-5/", 1000, 720, 1280))
        sqls = [sql for sql, _ in c.calls]
        ready = next(i for i, q in enumerate(sqls) if "UPDATE vod_media_asset SET status='READY'" in q)
        self.assertEqual(c.calls[ready][1][0], "assets/v1/7/3-5/")
        active = [(q, p) for q, p in c.calls[ready + 1:] if "vod_output_prefix" in q]
        self.assertIn("SET status='ACTIVE'", active[0][0])
        self.assertEqual(active[0][1], ("assets/v1/7/3-5/",))
        self.assertIn("SET status='GARBAGE'", active[1][0])
        self.assertIn("status IN ('WRITING','ACTIVE')", active[1][0])
        self.assertEqual(active[1][1], (7, "assets/v1/7/3-5/"))
        # 围栏丢了就什么前缀都不切
        c = FakeCursor(affected=0)
        fencing.commit_success(c, "core", 3, 5, 7, "assets/v1/7/3-5/", 1000, 720, 1280)
        self.assertFalse(any("vod_output_prefix" in q for q, _ in c.calls))

    def test_legacy_success_keeps_frozen_writes(self):
        c = FakeCursor(affected=1)
        self.assertTrue(fencing.commit_success(c, "legacy", 3, 5, 7, "assets/7/", 1000, 720, 1280,
                                               [("720p", 720, 1280, 2500)], episode_id=9))
        sqls = [s for s, _ in c.calls]
        self.assertIn("UPDATE transcode_job SET status='SUCCEEDED'", sqls[0])
        self.assertNotIn("error_code", sqls[0])
        self.assertTrue(any("INSERT INTO asset_rendition" in s for s in sqls))
        self.assertTrue(any("UPDATE video_asset SET status='READY'" in s for s in sqls))
        self.assertTrue(any("UPDATE episode SET asset_id" in s for s in sqls))
        self.assertFalse(any("vod_output_prefix" in s for s in sqls))

    def test_renew_same_second_still_holds(self):
        # PyMySQL：同一秒内再续租 locked_until 不变 -> 返回 0；回查围栏仍在就算成功
        c = FakeCursor(affected=0, rows=[(1,)])
        self.assertTrue(fencing.renew_lease(c, "core", 3, 5, 30))
        c = FakeCursor(affected=0, rows=[])
        self.assertFalse(fencing.renew_lease(c, "core", 3, 5, 30))

    def test_failure_sql_core_and_legacy(self):
        c = FakeCursor()
        fencing.record_failure(c, "core", 3, 5, False, "boom", "STORAGE_ERROR", 2500)
        sql, params = c.calls[0]
        self.assertIn("error_code=%s", sql)
        self.assertIn("next_attempt_at=NOW() + INTERVAL %s SECOND", sql)
        self.assertEqual(params, ("RETRYING", "boom", "STORAGE_ERROR", 3, 3, 5))
        c = FakeCursor()
        fencing.record_failure(c, "core", 3, 5, True, "bad", "NO_VIDEO_STREAM")
        self.assertIn("next_attempt_at=NULL", c.calls[0][0])
        self.assertEqual(c.calls[0][1], ("FAILED", "bad", "NO_VIDEO_STREAM", 3, 5))
        c = FakeCursor()
        fencing.record_failure(c, "legacy", 3, 5, False, "boom", None, 2500)
        self.assertNotIn("error_code", c.calls[0][0])
        self.assertNotIn("next_attempt_at", c.calls[0][0])


class RetryPolicyTest(unittest.TestCase):
    def test_backoff_within_jitter_window(self):
        for attempt, ceiling in ((1, 10_000), (2, 20_000), (3, 40_000)):
            lo = rp.backoff_ms(attempt, 10_000, 600_000, 0.5, rnd=lambda: 1.0)
            hi = rp.backoff_ms(attempt, 10_000, 600_000, 0.5, rnd=lambda: 0.0)
            self.assertEqual((lo, hi), (ceiling // 2, ceiling))
            for _ in range(200):
                self.assertTrue(ceiling // 2 <= rp.backoff_ms(attempt, 10_000, 600_000, 0.5) <= ceiling)

    def test_backoff_is_capped(self):
        self.assertEqual(rp.backoff_ms(30, 10_000, 60_000, 0.5, rnd=lambda: 0.0), 60_000)

    def test_classify(self):
        self.assertEqual(rp.classify(rp.JobError(rp.NO_VIDEO_STREAM, "x")), (rp.NO_VIDEO_STREAM, False))
        self.assertEqual(rp.classify(rp.JobError(rp.TIMEOUT, "x")), (rp.TIMEOUT, True))
        botocore_like = type("EndpointConnectionError", (Exception,), {"__module__": "botocore.exceptions"})
        self.assertEqual(rp.classify(botocore_like()), (rp.STORAGE_ERROR, True))
        pymysql_like = type("OperationalError", (Exception,), {"__module__": "pymysql.err"})
        self.assertEqual(rp.classify(pymysql_like()), (rp.DB_ERROR, True))
        self.assertEqual(rp.classify(ValueError("?")), (rp.INTERNAL, True))

    def test_plan_failure(self):
        bad = rp.JobError(rp.INPUT_INVALID, "x")
        self.assertEqual(rp.plan_failure("core", 1, 3, bad, 10, 100, 0.5), (True, rp.INPUT_INVALID, None))
        # 旧 Demo 不分类：仍按次数重试（Phase 0 冻结）
        final, code, delay = rp.plan_failure("legacy", 1, 3, bad, 10, 100, 0.5)
        self.assertEqual((final, code), (False, None))
        self.assertTrue(5 <= delay <= 10)
        self.assertEqual(rp.plan_failure("core", 3, 3, OSError("disk"), 10, 100, 0.5), (True, rp.IO_ERROR, None))


class patched:
    """临时替换 worker 模块里的名字。"""

    def __init__(self, **names):
        self.names, self.saved = names, {}

    def __enter__(self):
        for k, v in self.names.items():
            self.saved[k] = getattr(worker, k)
            setattr(worker, k, v)
        return self

    def __exit__(self, *exc):
        for k, v in self.saved.items():
            setattr(worker, k, v)


JOB_ROW = {"attempts": 1, "max_attempts": 3, "claim_version": 2, "id": 7, "source_bucket": "vod-source",
           "source_key": "raw/t1/7/source.mp4", "encrypted": 0}


def write(path, text="x"):
    with open(path, "w") as f:
        f.write(text)


def fake_hls(cmd, timeout=None, stop=None, fail_code=None):
    out = cmd[-1].rsplit("/%v/", 1)[0]
    os.makedirs(f"{out}/720p", exist_ok=True)
    for name in ("720p/index.m3u8", "720p/seg_00000.ts", "master.m3u8"):
        write(f"{out}/{name}", "#EXTM3U\n")
    return ""


class ProcessTest(unittest.TestCase):
    """process() 编排：数据库、对象存储、ffmpeg 全是假的。"""

    def setUp(self):
        self.calls = {"commit_success": [], "record_failure": [], "apply_failure": []}
        self.s3 = FakeS3()

    def run_process(self, job_id, origin="core", renew=lambda *a: True, row=None, **extra):
        cur = FakeCursor(rows=[dict(row or JOB_ROW)])
        conn = FakeConn(cur)
        calls = self.calls

        def commit_success(c, *a, **k):
            calls["commit_success"].append(a)
            return True

        def record_failure(c, origin_, job_id_, version, final, error, code=None, delay=None):
            calls["record_failure"].append((final, code, delay))
            return 1

        def apply_failure(c, *a):
            calls["apply_failure"].append(a)
            return True

        names = dict(db=lambda: conn, claim=lambda *a: True, mark_transcoding=lambda *a: 1,
                     renew_lease=renew, download_source=lambda b, k, d: write(d),
                     probe=lambda path, stop=None: (720, 1280, 8000, False), run=fake_hls,
                     verify_hls=lambda *a: True, s3c=self.s3, commit_success=commit_success,
                     record_failure=record_failure, apply_failure=apply_failure, HEARTBEAT_SEC=3600,
                     r=types.SimpleNamespace(delete=lambda *a: None))
        names.update(extra)
        with patched(**names):
            self.cursor = cur
            return worker.process(job_id, "trace", origin), conn

    def prefix_sql(self):
        return [(sql, params) for sql, params in self.cursor.calls if "vod_output_prefix" in sql]

    def test_success_uploads_master_last_and_commits(self):
        res, conn = self.run_process(101)
        self.assertEqual(res, "ok")
        self.assertEqual(self.s3.uploaded[-1], "assets/v1/7/101-2/master.m3u8")
        self.assertEqual(len(self.calls["commit_success"]), 1)
        self.assertEqual(self.calls["commit_success"][0][4], "assets/v1/7/101-2/")  # hls_prefix 指向任务专属前缀
        self.assertIn("commit", conn.events)

    def test_core_writes_job_scoped_prefix_and_records_writing_first(self):
        res, _ = self.run_process(111)
        self.assertEqual(res, "ok")
        self.assertTrue(all(k.startswith("assets/v1/7/111-2/") for k in self.s3.uploaded), self.s3.uploaded)
        sql, params = self.prefix_sql()[0]
        self.assertIn("INSERT IGNORE INTO vod_output_prefix", sql)
        self.assertIn("'WRITING'", sql)
        self.assertEqual(params, (7, 111, 2, "assets/v1/7/111-2/"))

    def test_remote_missing_or_wrong_size_does_not_commit(self):
        for name, mutate in (("missing", lambda o: {k: v for k, v in o.items() if not k.endswith("seg_00000.ts")}),
                             ("size", lambda o: {k: (v + 1 if k.endswith("index.m3u8") else v) for k, v in o.items()}),
                             ("extra", lambda o: {**o, "assets/v1/7/112-2/stray.ts": 1})):
            self.setUp()
            self.s3 = FakeS3(mutate)
            res, conn = self.run_process(112)
            self.assertEqual(res[0], "retry", name)
            self.assertEqual(self.calls["commit_success"], [], name)
            self.assertEqual(self.calls["record_failure"][0][:2], (False, rp.STORAGE_ERROR), name)
            discards = [p for sql, p in self.prefix_sql() if "SET status='GARBAGE'" in sql]
            self.assertEqual(discards, [("assets/v1/7/112-2/",)], name)  # 自己的 WRITING 置 GARBAGE

    def test_fence_lost_after_writing_discards_prefix(self):
        answers = [True, False]
        res, _ = self.run_process(113, renew=lambda *a: answers.pop(0))
        self.assertEqual(res, "drop")
        discards = [p for sql, p in self.prefix_sql() if "SET status='GARBAGE'" in sql]
        self.assertEqual(discards, [("assets/v1/7/113-2/",)])

    def test_legacy_keeps_fixed_prefix_and_skips_prefix_table(self):
        res, _ = self.run_process(114, origin="legacy", row=dict(JOB_ROW, episode_id=9))
        self.assertEqual(res, "ok")
        self.assertEqual(self.s3.uploaded[-1], "assets/7/master.m3u8")
        self.assertEqual(self.prefix_sql(), [])

    def test_encrypted_key_table_and_uri_by_origin(self):
        for origin, table, uri in (("core", "vod_content_key", "/keys/v1/7"), ("legacy", "content_key", "/keys/7")):
            seen = {}

            def capture(cmd, timeout=None, stop=None, fail_code=None):
                info = cmd[cmd.index("-hls_key_info_file") + 1]
                seen["keyinfo"] = open(info).read().splitlines()
                return fake_hls(cmd)

            self.setUp()
            row = dict(JOB_ROW, encrypted=1, episode_id=9 if origin == "legacy" else None)
            key_row = {"key_hex": "00" * 16, "iv_hex": "11" * 16}
            cur = FakeCursor(rows=[row, key_row])
            conn = FakeConn(cur)
            with patched(db=lambda: conn, claim=lambda *a: True, mark_transcoding=lambda *a: 1,
                         renew_lease=lambda *a: True, download_source=lambda b, k, d: write(d),
                         probe=lambda path, stop=None: (720, 1280, 8000, False), run=capture,
                         verify_hls=lambda *a: True, s3c=self.s3, commit_success=lambda *a, **k: True,
                         HEARTBEAT_SEC=3600, r=types.SimpleNamespace(delete=lambda *a: None)):
                self.assertEqual(worker.process(115, "t", origin), "ok", origin)
            key_sql = [sql for sql, _ in cur.calls if "key_hex" in sql]
            self.assertTrue(key_sql and all(table + "(" in sql or "FROM " + table + " " in sql for sql in key_sql),
                            (origin, key_sql))
            self.assertEqual(seen["keyinfo"][0], uri, origin)
            self.assertEqual(seen["keyinfo"][2], "11" * 16)

    def test_fence_lost_before_upload_uploads_nothing(self):
        before = metric_value(worker.DROP_FENCED)
        res, conn = self.run_process(102, renew=lambda *a: False)
        self.assertEqual(res, "drop")
        self.assertEqual(self.s3.uploaded, [])
        self.assertEqual(self.calls["commit_success"], [])
        self.assertEqual(self.calls["record_failure"], [])  # 不写 RETRYING/FAILED，不进 retry/DLQ
        self.assertEqual(metric_value(worker.DROP_FENCED), before + 1)

    def test_fence_lost_before_master_skips_master_and_ready(self):
        answers = [True, False]  # 上传前复核通过；传 master 前复核失败
        res, _ = self.run_process(103, renew=lambda *a: answers.pop(0))
        self.assertEqual(res, "drop")
        self.assertTrue(self.s3.uploaded)
        self.assertNotIn("assets/v1/7/103-2/master.m3u8", self.s3.uploaded)
        self.assertEqual(self.calls["commit_success"], [])
        self.assertEqual(self.calls["record_failure"], [])

    def test_commit_fence_lost_rolls_back(self):
        res, conn = self.run_process(104, commit_success=lambda *a, **k: False)
        self.assertEqual(res, "drop")
        self.assertIn("rollback", conn.events)
        self.assertNotIn("commit", conn.events)

    def test_cancel_while_ffmpeg_runs_kills_it_and_drops(self):
        # 取消把任务改成 CANCELLED 并 +1 版本：心跳续租失败 -> 杀掉 ffmpeg -> 不写 READY、不进 retry/DLQ
        before = metric_value(worker.LEASE_LOST)
        t0 = time.time()
        res, _ = self.run_process(105, renew=lambda *a: False, HEARTBEAT_SEC=0.2,
                                  run=worker.run, build_ffmpeg=lambda *a: ["sleep", "30"])
        self.assertEqual(res, "drop")
        self.assertLess(time.time() - t0, 10)
        self.assertEqual(self.s3.uploaded, [])
        self.assertEqual(self.calls["commit_success"], [])
        self.assertEqual(self.calls["record_failure"], [])
        self.assertEqual(metric_value(worker.LEASE_LOST), before + 1)

    def test_non_retryable_core_error_fails_on_first_attempt(self):
        def no_video(path, stop=None):
            raise rp.JobError(rp.NO_VIDEO_STREAM, "no video stream")
        res, _ = self.run_process(106, probe=no_video)
        self.assertEqual(res, "dlq")
        self.assertEqual(self.calls["record_failure"], [(True, rp.NO_VIDEO_STREAM, None)])

    def test_retryable_error_backs_off_with_jitter(self):
        def storage_down(b, k, d):
            raise OSError("connection refused")
        res, _ = self.run_process(107, download_source=storage_down, RETRY_BASE_MS=10_000, RETRY_JITTER=0.5)
        self.assertEqual(res[0], "retry")
        self.assertTrue(5_000 <= res[1] <= 10_000, res)
        final, code, delay = self.calls["record_failure"][0]
        self.assertEqual((final, code, delay), (False, rp.IO_ERROR, res[1]))

    def test_last_retryable_attempt_goes_to_dlq(self):
        def storage_down(b, k, d):
            raise OSError("connection refused")
        res, _ = self.run_process(108, download_source=storage_down, row=dict(JOB_ROW, attempts=3))
        self.assertEqual(res, "dlq")
        self.assertEqual(self.calls["record_failure"], [(True, rp.IO_ERROR, None)])

    def test_failure_with_lost_fence_is_dropped(self):
        def storage_down(b, k, d):
            raise OSError("connection refused")
        res, _ = self.run_process(109, download_source=storage_down, apply_failure=lambda *a: False)
        self.assertEqual(res, "drop")

    def test_legacy_non_retryable_still_retries(self):
        def no_video(path, stop=None):
            raise rp.JobError(rp.NO_VIDEO_STREAM, "no video stream")
        row = dict(JOB_ROW, episode_id=9)
        res, _ = self.run_process(110, origin="legacy", probe=no_video, row=row)
        self.assertEqual(res[0], "retry")
        self.assertEqual(self.calls["record_failure"][0][:2], (False, None))


class TranscodingLockOrderTest(unittest.TestCase):
    """Phase 3 遗留：TRANSCODING 显式事务，先 FOR UPDATE 锁任务行，再改资产（与取消事务同序）。"""

    def test_locks_job_row_before_asset(self):
        for origin, job, asset in (("core", "vod_transcode_job", "vod_media_asset"),
                                   ("legacy", "transcode_job", "video_asset")):
            cur = FakeCursor(rows=[{"claim_version": 5, "status": "RUNNING"}])
            conn = FakeConn(cur)
            self.assertEqual(fencing.mark_transcoding(conn, cur, origin, 7, 3, 5), 1)
            self.assertEqual(conn.events, ["begin", "commit"])
            (lock_sql, lock_params), (upd_sql, upd_params) = cur.calls
            self.assertTrue(lock_sql.startswith(f"SELECT claim_version, status FROM {job} "), lock_sql)
            self.assertTrue(lock_sql.rstrip().endswith("FOR UPDATE"))
            self.assertEqual(lock_params, (3,))
            self.assertEqual(upd_sql, f"UPDATE {asset} SET status='TRANSCODING' WHERE id=%s")
            self.assertNotIn("JOIN", upd_sql)
            self.assertEqual(upd_params, (7,))

    def test_lost_fence_rolls_back_without_touching_asset(self):
        for row in ({"claim_version": 6, "status": "RUNNING"}, {"claim_version": 5, "status": "CANCELLED"}, None):
            cur = FakeCursor(rows=[row] if row else [])
            conn = FakeConn(cur)
            self.assertEqual(fencing.mark_transcoding(conn, cur, "core", 7, 3, 5), 0)
            self.assertEqual(conn.events, ["begin", "rollback"])
            self.assertEqual(len(cur.calls), 1)


class RunTest(unittest.TestCase):
    def test_timeout_is_retryable_job_error(self):
        with self.assertRaises(rp.JobError) as ctx:
            worker.run(["sleep", "5"], timeout=0.3)
        self.assertEqual(ctx.exception.code, rp.TIMEOUT)
        self.assertTrue(ctx.exception.retryable)

    def test_stop_event_kills_process(self):
        stop = threading.Event()
        threading.Timer(0.2, stop.set).start()
        t0 = time.time()
        with self.assertRaises(rp.FenceLost):
            worker.run(["sleep", "30"], timeout=60, stop=stop)
        self.assertLess(time.time() - t0, 5)

    def test_nonzero_exit_uses_fail_code(self):
        with self.assertRaises(rp.JobError) as ctx:
            worker.run(["sh", "-c", "echo bad >&2; exit 3"], fail_code=rp.INPUT_INVALID)
        self.assertEqual(ctx.exception.code, rp.INPUT_INVALID)
        self.assertFalse(ctx.exception.retryable)

    def test_probe_rejects_audio_only_source(self):
        with patched(run=lambda *a, **k: '{"streams":[{"codec_type":"audio"}],"format":{"duration":"1"}}'):
            with self.assertRaises(rp.JobError) as ctx:
                worker.probe("/dev/null")
        self.assertEqual(ctx.exception.code, rp.NO_VIDEO_STREAM)

    def test_metrics_endpoint_exposes_new_series(self):
        try:
            from prometheus_client import generate_latest
        except ImportError:
            self.skipTest("prometheus_client not installed")
        text = generate_latest().decode()
        for name in ("vod_worker_heartbeat_timestamp_seconds", "vod_worker_lease_lost_total",
                     "vod_worker_drop_fenced_total", "vod_transcode_inflight"):
            self.assertIn(name, text)

    def test_heartbeat_config_is_clamped_below_lease(self):
        self.assertLessEqual(worker.HEARTBEAT_SEC * 3, max(3, worker.LEASE_SEC))


if __name__ == "__main__":
    unittest.main(verbosity=2)
