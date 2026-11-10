#!/usr/bin/env python3
"""不需要 Docker/MySQL。Phase 4 输出前缀清理：只删过了宽限期的 GARBAGE 任务专属前缀，
删前复核不是资产当前 hls_prefix；ACTIVE、固定前缀、旧 Demo 前缀永远不删。SQL 在 test_fencing_mysql.py 里用真实 MySQL 核对。"""
import os, sys, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "worker"))
import output_gc  # noqa: E402
import output_prefix  # noqa: E402


class FakeCursor:
    """due_garbage 返回 due；复核 SELECT 按 id 查 state。"""

    def __init__(self, due=(), state=None):
        self.due, self.state, self.calls, self._last = list(due), dict(state or {}), [], None

    def execute(self, sql, params=None):
        self.calls.append((sql, params))
        self._last = (sql, params)
        return 0

    def fetchall(self):
        return self.due

    def fetchone(self):
        sql, params = self._last
        return self.state.get(params[0])


class FakeS3:
    def __init__(self, keys, fail=False):
        self.keys, self.fail, self.deletes, self.lists = set(keys), fail, [], 0

    def list_objects_v2(self, Bucket, Prefix, MaxKeys=1000):
        self.lists += 1
        keys = sorted(k for k in self.keys if k.startswith(Prefix))[:MaxKeys]
        return {"Contents": [{"Key": k, "Size": 1} for k in keys]} if keys else {}

    def delete_objects(self, Bucket, Delete):
        keys = [o["Key"] for o in Delete["Objects"]]
        self.deletes.append(keys)
        if self.fail:
            return {"Errors": [{"Key": keys[0]}]}
        self.keys -= set(keys)
        return {}


def row(i, asset, prefix):
    return {"id": i, "asset_id": asset, "prefix": prefix}


class GcTest(unittest.TestCase):
    def test_promote_reads_without_locks_then_updates_prefix_rows_only(self):
        # 不能是 UPDATE ... JOIN 任务表：那样会先锁前缀再等任务行，和取消/成功事务（先任务后前缀）反向
        c = FakeCursor(due=[{"id": 3}, {"id": 8}])
        c.execute = lambda sql, params=None: (c.calls.append((sql, params)), 1)[1]
        self.assertEqual(output_gc.promote_stale_writing(c), 2)
        (select, _), *updates = c.calls
        self.assertTrue(select.startswith("SELECT p.id FROM vod_output_prefix p LEFT JOIN vod_transcode_job"))
        self.assertNotIn("FOR UPDATE", select)
        for cond in ("p.status='WRITING'", "j.claim_version<>p.claim_version", "j.status<>'RUNNING'", "j.id IS NULL"):
            self.assertIn(cond, select)
        self.assertEqual([p for _, p in updates], [(3,), (8,)])
        for sql, _ in updates:
            self.assertTrue(sql.startswith("UPDATE vod_output_prefix SET status='GARBAGE'"), sql)
            self.assertIn("WHERE id=%s AND status='WRITING'", sql)
            self.assertNotIn("JOIN", sql)
            self.assertNotIn("vod_transcode_job", sql)

    def test_due_selects_only_garbage_past_grace(self):
        c = FakeCursor()
        output_gc.due_garbage(c, 7200, 20)
        sql, params = c.calls[0]
        self.assertIn("status='GARBAGE'", sql)
        self.assertNotIn("ACTIVE", sql)
        self.assertIn("updated_at < NOW() - INTERVAL %s SECOND", sql)
        self.assertEqual(params, (7200, 20))

    def test_deletes_garbage_in_batches_and_marks_deleted(self):
        keys = {f"assets/v1/5/9-1/720p/seg_{i:05d}.ts" for i in range(7)} | {"assets/v1/5/9-1/master.m3u8",
                                                                            "assets/v1/5/9-2/master.m3u8"}
        s3 = FakeS3(keys)
        c = FakeCursor(due=[row(1, 5, "assets/v1/5/9-1/")],
                       state={1: {"status": "GARBAGE", "hls_prefix": "assets/v1/5/9-2/"}})
        self.assertEqual(output_gc.gc_pass(c, s3, "vod-hls", 3600, 20, 3), (1, 8, 0))
        self.assertTrue(all(len(batch) <= 3 for batch in s3.deletes))
        self.assertEqual(s3.keys, {"assets/v1/5/9-2/master.m3u8"})  # ACTIVE 前缀原样
        deleted = [p for sql, p in c.calls if "SET status='DELETED'" in sql]
        self.assertEqual(deleted, [(1,)])
        self.assertIn("status='GARBAGE'", [sql for sql, _ in c.calls if "DELETED" in sql][0])

    def test_never_deletes_current_hls_prefix_or_non_garbage(self):
        s3 = FakeS3({"assets/v1/5/9-1/master.m3u8", "assets/v1/6/3-1/master.m3u8"})
        c = FakeCursor(due=[row(1, 5, "assets/v1/5/9-1/"), row(2, 6, "assets/v1/6/3-1/")],
                       state={1: {"status": "GARBAGE", "hls_prefix": "assets/v1/5/9-1/"},  # 删前复核：是当前前缀
                              2: {"status": "ACTIVE", "hls_prefix": "assets/v1/6/4-1/"}})  # 期间被激活
        self.assertEqual(output_gc.gc_pass(c, s3, "vod-hls", 3600, 20, 100), (0, 0, 2))
        self.assertEqual(s3.deletes, [])
        self.assertFalse(any("DELETED" in sql for sql, _ in c.calls))

    def test_fixed_and_legacy_prefixes_are_never_touched(self):
        for prefix, asset in (("assets/v1/5/", 5), ("assets/5/", 5), ("assets/v1/6/9-1/", 5), ("assets/v1/5/9-1", 5),
                              ("assets/v1/5/../6/9-1/", 5), ("", 5)):
            s3 = FakeS3({"assets/v1/5/master.m3u8", "assets/5/master.m3u8", "assets/v1/6/9-1/master.m3u8"})
            c = FakeCursor(due=[row(1, asset, prefix)], state={1: {"status": "GARBAGE", "hls_prefix": None}})
            self.assertEqual(output_gc.gc_pass(c, s3, "vod-hls", 3600, 20, 100), (0, 0, 1), prefix)
            self.assertEqual(s3.deletes, [], prefix)
            with self.assertRaises(ValueError):
                output_gc.delete_prefix(s3, "vod-hls", prefix, asset, 100)

    def test_partial_delete_failure_keeps_row_garbage(self):
        s3 = FakeS3({"assets/v1/5/9-1/master.m3u8"}, fail=True)
        c = FakeCursor(due=[row(1, 5, "assets/v1/5/9-1/")], state={1: {"status": "GARBAGE", "hls_prefix": None}})
        with self.assertRaises(RuntimeError):
            output_gc.gc_pass(c, s3, "vod-hls", 3600, 20, 100)
        self.assertFalse(any("DELETED" in sql for sql, _ in c.calls))


class PrefixStateTest(unittest.TestCase):
    def test_writing_activate_discard_sql(self):
        c = FakeCursor()
        output_prefix.insert_writing(c, 5, 9, 2, "assets/v1/5/9-2/")
        c.execute = lambda sql, params=None, _orig=c.execute: (_orig(sql, params), 1)[1]
        self.assertTrue(output_prefix.activate(c, 5, "assets/v1/5/9-2/"))
        output_prefix.discard(c, "assets/v1/5/9-3/")
        (ins, ip), (act, ap), (gar, gp), (dis, dp) = c.calls
        self.assertIn("INSERT IGNORE", ins)
        self.assertEqual(ip, (5, 9, 2, "assets/v1/5/9-2/"))
        self.assertIn("SET status='ACTIVE'", act)
        self.assertIn("status='WRITING'", act)          # 只允许 WRITING → ACTIVE
        self.assertNotIn("GARBAGE", act)
        self.assertIn("SET status='GARBAGE'", gar)
        self.assertIn("prefix<>%s", gar)
        self.assertIn("SET status='GARBAGE'", dis)
        self.assertIn("status='WRITING'", dis)   # discard 不会把 ACTIVE 降级
        self.assertEqual(dp, ("assets/v1/5/9-3/",))


    def test_activate_refuses_non_writing_prefix(self):
        c = FakeCursor()   # execute 返回 0：自己的前缀已不是 WRITING
        self.assertFalse(output_prefix.activate(c, 5, "assets/v1/5/9-2/"))
        self.assertEqual(len(c.calls), 1)  # 不再去改同资产的其他前缀


class GraceTest(unittest.TestCase):
    def test_grace_is_never_shorter_than_lease(self):
        try:
            sys.path.insert(0, os.path.dirname(__file__))
            import test_worker_reliability  # noqa: F401  复用它的依赖空壳
            import worker
        except SystemExit as e:
            self.skipTest(str(e))
        self.assertEqual(worker.gc_grace_seconds(30, 20), 30)
        self.assertEqual(worker.gc_grace_seconds(10, 20), 20)


if __name__ == "__main__":
    unittest.main(verbosity=2)
