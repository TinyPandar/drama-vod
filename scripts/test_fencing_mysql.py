#!/usr/bin/env python3
"""连真实 MySQL 核对 claim_version 围栏与 affected rows 语义（假游标测不出来的部分）。

会新建一个临时库（默认 vod_fence_test，跑完删除），在里面重放 mysql/init.sql 和 Flyway V1..Vn，
然后直接调用 worker/fencing.py、scripts/dlq.py 里的 SQL。不碰业务库。

连接（需要建库/删库权限，compose 下用 root）：
  MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=root MYSQL_PASSWORD=... python3 scripts/test_fencing_mysql.py
可选：FENCE_TEST_DB=库名，FENCE_TEST_KEEP=1 保留库便于排查。需要 PyMySQL。
"""
import glob, os, re, sys, threading, time, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
sys.path[:0] = [os.path.join(ROOT, "worker"), HERE]

import pymysql  # noqa: E402
from pymysql.constants import CLIENT  # noqa: E402

import fencing  # noqa: E402
from dlq import ReplayRefused, replay_job  # noqa: E402

DB = os.environ.get("FENCE_TEST_DB", "vod_fence_test")
if not re.fullmatch(r"[A-Za-z0-9_]+", DB):
    raise SystemExit("FENCE_TEST_DB must be [A-Za-z0-9_]+")

# 与 vod-api JobService.cancel 的条件 UPDATE 保持一致
CANCEL_SQL = ("UPDATE vod_transcode_job SET status='CANCELLED', claim_version=claim_version+1, locked_until=NULL, "
              "next_attempt_at=NULL, updated_at=CURRENT_TIMESTAMP "
              "WHERE id=%s AND tenant_id=%s AND status IN ('PENDING','RETRYING','RUNNING')")


def connect(database=None, found_rows=False, autocommit=True):
    return pymysql.connect(host=os.environ.get("MYSQL_HOST", "127.0.0.1"),
                           port=int(os.environ.get("MYSQL_PORT", "3306")),
                           user=os.environ.get("MYSQL_USER", "root"),
                           password=os.environ.get("MYSQL_PASSWORD", ""),
                           database=database, autocommit=autocommit, charset="utf8mb4",
                           cursorclass=pymysql.cursors.DictCursor,
                           client_flag=CLIENT.FOUND_ROWS if found_rows else 0)


def statements(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    text = "\n".join(line.split("--", 1)[0] for line in text.splitlines())
    for stmt in text.split(";"):
        stmt = stmt.strip()
        if stmt and not stmt.upper().startswith("USE "):
            yield stmt


def migrations():
    files = glob.glob(os.path.join(ROOT, "vod-api", "src", "main", "resources", "db", "migration", "V*__*.sql"))
    return sorted(files, key=lambda p: int(re.match(r"V(\d+)__", os.path.basename(p)).group(1)))


def setUpModule():
    admin = connect()
    with admin.cursor() as c:
        c.execute(f"DROP DATABASE IF EXISTS {DB}")
        c.execute(f"CREATE DATABASE {DB} CHARACTER SET utf8mb4")
        c.execute("SELECT VERSION() AS v")
        print("mysql", c.fetchone()["v"], "db", DB, flush=True)
    admin.close()
    conn = connect(DB)
    with conn.cursor() as c:
        for path in [os.path.join(ROOT, "mysql", "init.sql")] + migrations():
            for stmt in statements(path):
                c.execute(stmt)
    conn.close()


def tearDownModule():
    if os.environ.get("FENCE_TEST_KEEP") == "1":
        return
    admin = connect()
    with admin.cursor() as c:
        c.execute(f"DROP DATABASE IF EXISTS {DB}")
    admin.close()


class Base(unittest.TestCase):
    def setUp(self):
        self.conn = connect(DB)
        self.c = self.conn.cursor()
        for t in ("vod_outbox", "vod_transcode_job", "vod_media_asset", "transcode_job", "video_asset",
                  "asset_rendition", "episode", "drama"):
            self.c.execute(f"DELETE FROM {t}")

    def tearDown(self):
        self.conn.close()

    def core_job(self, status="PENDING", asset_status="UPLOADED", **cols):
        self.c.execute("INSERT INTO vod_media_asset(tenant_id, status, source_bucket, source_key) "
                       "VALUES (1, %s, 'vod-source', 'raw/t1/x/source.mp4')", (asset_status,))
        asset_id = self.c.lastrowid
        self.c.execute("INSERT INTO vod_transcode_job(tenant_id, asset_id, profile, status) VALUES (1,%s,'ladder_v1',%s)",
                       (asset_id, status))
        job_id = self.c.lastrowid
        for k, v in cols.items():
            self.c.execute(f"UPDATE vod_transcode_job SET {k}=%s WHERE id=%s", (v, job_id))
        return job_id, asset_id

    def legacy_job(self):
        self.c.execute("INSERT INTO drama(title) VALUES ('d')")
        drama_id = self.c.lastrowid
        self.c.execute("INSERT INTO episode(drama_id, ep_no, title) VALUES (%s, 1, 'e')", (drama_id,))
        episode_id = self.c.lastrowid
        self.c.execute("INSERT INTO video_asset(episode_id, source_bucket, source_key, status) "
                       "VALUES (%s, 'vod-source', 'raw/x/source.mp4', 'UPLOADED')", (episode_id,))
        asset_id = self.c.lastrowid
        self.c.execute("INSERT INTO transcode_job(asset_id) VALUES (%s)", (asset_id,))
        return self.c.lastrowid, asset_id, episode_id

    def job(self, job_id, table="vod_transcode_job"):
        self.c.execute(f"SELECT * FROM {table} WHERE id=%s", (job_id,))
        return self.c.fetchone()

    def asset_status(self, asset_id, table="vod_media_asset"):
        self.c.execute(f"SELECT status FROM {table} WHERE id=%s", (asset_id,))
        return self.c.fetchone()["status"]

    def expire_lease(self, job_id, table="vod_transcode_job"):
        self.c.execute(f"UPDATE {table} SET locked_until=NOW() - INTERVAL 5 SECOND WHERE id=%s", (job_id,))


class AffectedRowsSemantics(Base):
    """PyMySQL 默认只统计实际变化的行；带 CLIENT_FOUND_ROWS（Connector/J 默认 useAffectedRows=false
    就是这样）统计匹配行。worker 代码按前者写，vod-api 的条件 UPDATE 都让「匹配」必然「变化」。"""

    def test_pymysql_counts_changed_rows_found_rows_counts_matched(self):
        job_id, asset_id = self.core_job(asset_status="TRANSCODING")
        same = "UPDATE vod_media_asset SET status='TRANSCODING' WHERE id=%s"
        self.assertEqual(self.c.execute(same, (asset_id,)), 0)
        fr = connect(DB, found_rows=True)
        with fr.cursor() as c:
            self.assertEqual(c.execute(same, (asset_id,)), 1)
        fr.close()

    def test_renew_in_same_second_is_zero_but_fence_held(self):
        job_id, _ = self.core_job()
        self.assertTrue(fencing.claim(self.c, "core", job_id, "w1", 60))
        v = self.job(job_id)["claim_version"]
        self.c.execute("UPDATE vod_transcode_job SET locked_until='2030-01-01 00:00:00' WHERE id=%s", (job_id,))
        # 同一个值再写一次：PyMySQL 返回 0，正是续租在同一秒内的情况
        self.assertEqual(self.c.execute(
            "UPDATE vod_transcode_job SET locked_until='2030-01-01 00:00:00' WHERE id=%s AND claim_version=%s "
            "AND status='RUNNING'", (job_id, v)), 0)
        self.assertTrue(fencing.renew_lease(self.c, "core", job_id, v, 60))
        self.assertTrue(fencing.renew_lease(self.c, "core", job_id, v, 60))  # 紧接着再续，大概率同一秒
        self.assertFalse(fencing.renew_lease(self.c, "core", job_id, v - 1, 60))


class ClaimAndLease(Base):
    def test_lease_valid_blocks_claim_and_expired_lease_allows_it(self):
        job_id, _ = self.core_job()
        self.assertTrue(fencing.claim(self.c, "core", job_id, "w1", 60))
        first = self.job(job_id)
        self.assertEqual((first["status"], first["claim_version"], first["attempts"]), ("RUNNING", 1, 1))
        # 重投：租约有效，不抢
        self.assertFalse(fencing.claim(self.c, "core", job_id, "w2", 60))
        self.assertEqual(self.job(job_id)["worker_id"], "w1")
        self.expire_lease(job_id)
        self.assertTrue(fencing.claim(self.c, "core", job_id, "w2", 60))
        second = self.job(job_id)
        self.assertEqual((second["claim_version"], second["attempts"], second["worker_id"]), (2, 2, "w2"))

    def test_claim_maintains_updated_at(self):
        job_id, _ = self.core_job()
        self.c.execute("UPDATE vod_transcode_job SET updated_at=NOW() - INTERVAL 1 HOUR WHERE id=%s", (job_id,))
        fencing.claim(self.c, "core", job_id, "w1", 60)
        self.c.execute("SELECT TIMESTAMPDIFF(SECOND, updated_at, NOW()) AS age FROM vod_transcode_job WHERE id=%s",
                       (job_id,))
        self.assertLess(self.c.fetchone()["age"], 60)

    def test_legacy_claim_works_without_core_columns(self):
        job_id, asset_id, _ = self.legacy_job()
        self.assertTrue(fencing.claim(self.c, "legacy", job_id, "w1", 60))
        self.assertFalse(fencing.claim(self.c, "legacy", job_id, "w2", 60))
        self.expire_lease(job_id, "transcode_job")
        self.assertTrue(fencing.claim(self.c, "legacy", job_id, "w2", 60))
        self.assertEqual(self.job(job_id, "transcode_job")["claim_version"], 2)


class StaleClaimCannotWrite(Base):
    def stale_and_current(self, origin="core"):
        if origin == "core":
            job_id, asset_id = self.core_job()
            episode_id = None
        else:
            job_id, asset_id, episode_id = self.legacy_job()
        table = "vod_transcode_job" if origin == "core" else "transcode_job"
        fencing.claim(self.c, origin, job_id, "old", 60)
        old = self.job(job_id, table)["claim_version"]
        self.expire_lease(job_id, table)
        fencing.claim(self.c, origin, job_id, "new", 60)
        return job_id, asset_id, episode_id, old, old + 1

    def check(self, origin):
        asset_table = fencing.ASSET_TABLE[origin]
        job_table = "vod_transcode_job" if origin == "core" else "transcode_job"
        job_id, asset_id, episode_id, old, new = self.stale_and_current(origin)
        self.assertEqual(fencing.update_asset_status(self.c, origin, asset_id, job_id, new, "TRANSCODING"), 1)
        self.assertEqual(fencing.update_asset_status(self.c, origin, asset_id, job_id, old, "FAILED"), 0)
        self.assertEqual(self.asset_status(asset_id, asset_table), "TRANSCODING")
        # 旧 worker 失败收尾：任务行没抢到，资产不写 FAILED
        changed = fencing.record_failure(self.c, origin, job_id, old, True, "late", "STORAGE_ERROR")
        self.assertEqual(changed, 0)
        self.assertFalse(fencing.apply_failure(self.c, origin, asset_id, job_id, old, changed, True))
        # 旧 worker 成功提交：任务不 SUCCEEDED、资产不 READY
        self.conn.begin()
        ok = fencing.commit_success(self.c, origin, job_id, old, asset_id, "p/", 1000, 720, 1280,
                                    [("720p", 720, 1280, 2500)], episode_id)
        self.assertFalse(ok)
        self.conn.rollback()
        self.assertEqual(self.job(job_id, job_table)["status"], "RUNNING")
        self.assertEqual(self.asset_status(asset_id, asset_table), "TRANSCODING")
        # 当前持有者正常提交
        self.conn.begin()
        self.assertTrue(fencing.commit_success(self.c, origin, job_id, new, asset_id, "p/", 1000, 720, 1280,
                                               [("720p", 720, 1280, 2500)], episode_id))
        self.conn.commit()
        self.assertEqual(self.job(job_id, job_table)["status"], "SUCCEEDED")
        self.assertEqual(self.asset_status(asset_id, asset_table), "READY")
        # 成功之后旧 worker 也写不回 FAILED
        self.assertEqual(fencing.update_asset_status(self.c, origin, asset_id, job_id, old, "FAILED"), 0)
        self.assertEqual(self.asset_status(asset_id, asset_table), "READY")

    def test_core(self):
        self.check("core")

    def test_legacy(self):
        self.check("legacy")

    def test_same_status_still_counts_as_fence_held(self):
        job_id, asset_id = self.core_job(asset_status="TRANSCODING")
        fencing.claim(self.c, "core", job_id, "w1", 60)
        v = self.job(job_id)["claim_version"]
        raw = self.c.execute("UPDATE vod_media_asset a JOIN vod_transcode_job j ON j.asset_id=a.id "
                             "SET a.status='TRANSCODING' WHERE a.id=%s AND j.id=%s AND j.claim_version=%s",
                             (asset_id, job_id, v))
        self.assertEqual(raw, 0)  # 值没变：PyMySQL 返回 0
        self.assertEqual(fencing.update_asset_status(self.c, "core", asset_id, job_id, v, "TRANSCODING"), 1)
        self.assertEqual(fencing.update_asset_status(self.c, "core", asset_id, job_id, v - 1, "TRANSCODING"), 0)

    def test_failure_writes_error_code_and_next_attempt(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w1", 60)
        v = self.job(job_id)["claim_version"]
        self.assertEqual(fencing.record_failure(self.c, "core", job_id, v, False, "boom", "STORAGE_ERROR", 2500), 1)
        row = self.job(job_id)
        self.assertEqual((row["status"], row["error_code"], row["locked_until"]), ("RETRYING", "STORAGE_ERROR", None))
        self.c.execute("SELECT TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at) AS d FROM vod_transcode_job WHERE id=%s",
                       (job_id,))
        self.assertTrue(1 <= self.c.fetchone()["d"] <= 3)


class SuccessTransaction(Base):
    def test_failure_mid_transaction_rolls_back_job_and_asset(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w1", 60)
        v = self.job(job_id)["claim_version"]
        fencing.update_asset_status(self.c, "core", asset_id, job_id, v, "TRANSCODING")

        class Boom(Exception):
            pass

        class FailOnAsset:
            def __init__(self, c):
                self.c = c

            def execute(self, sql, params=None):
                if sql.startswith("UPDATE vod_media_asset SET status='READY'"):
                    raise Boom()
                return self.c.execute(sql, params)

            def fetchone(self):
                return self.c.fetchone()

        self.conn.begin()
        with self.assertRaises(Boom):
            fencing.commit_success(FailOnAsset(self.c), "core", job_id, v, asset_id, "p/", 1, 2, 2)
        self.conn.rollback()
        self.assertEqual(self.job(job_id)["status"], "RUNNING")  # 任务 SUCCEEDED 已执行过，也一起回滚
        self.assertEqual(self.asset_status(asset_id), "TRANSCODING")

    def test_cancel_waits_for_success_commit_and_then_conflicts(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w1", 60)
        v = self.job(job_id)["claim_version"]
        self.conn.begin()
        self.assertTrue(fencing.commit_success(self.c, "core", job_id, v, asset_id, "p/", 1, 2, 2))
        result = {}

        def cancel():
            other = connect(DB)
            with other.cursor() as oc:
                result["n"] = oc.execute(CANCEL_SQL, (job_id, 1))  # 等成功事务释放任务行锁
            other.close()

        t = threading.Thread(target=cancel)
        t.start()
        time.sleep(0.5)
        self.assertTrue(t.is_alive())  # 被行锁挡住
        self.conn.commit()
        t.join(10)
        self.assertEqual(result["n"], 0)  # SUCCEEDED 之后取消不到 -> 接口返回 409
        self.assertEqual(self.asset_status(asset_id), "READY")


class CancelFence(Base):
    def test_cancel_loses_fence_for_running_worker(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w1", 60)
        v = self.job(job_id)["claim_version"]
        fencing.update_asset_status(self.c, "core", asset_id, job_id, v, "TRANSCODING")
        self.assertEqual(self.c.execute(CANCEL_SQL, (job_id, 1)), 1)
        self.c.execute("UPDATE vod_media_asset SET status='CANCELLED' WHERE id=%s AND status <> 'READY'", (asset_id,))
        self.assertFalse(fencing.renew_lease(self.c, "core", job_id, v, 60))  # 心跳发现围栏丢失
        self.assertEqual(fencing.update_asset_status(self.c, "core", asset_id, job_id, v, "TRANSCODING"), 0)
        self.conn.begin()
        self.assertFalse(fencing.commit_success(self.c, "core", job_id, v, asset_id, "p/", 1, 2, 2))
        self.conn.rollback()
        self.assertEqual(fencing.record_failure(self.c, "core", job_id, v, False, "x", "TIMEOUT", 1000), 0)
        self.assertFalse(fencing.claim(self.c, "core", job_id, "w2", 60))  # 取消后不会再被领取
        self.assertEqual(self.job(job_id)["status"], "CANCELLED")
        self.assertEqual(self.asset_status(asset_id), "CANCELLED")
        self.assertEqual(self.c.execute(CANCEL_SQL, (job_id, 1)), 0)  # 再取消：0 行，接口按已取消幂等返回


class Sweeper(Base):
    def test_due_jobs(self):
        stale_pending, _ = self.core_job("PENDING")
        fresh_pending, _ = self.core_job("PENDING")
        expired, _ = self.core_job("RUNNING")
        retry_due, _ = self.core_job("RETRYING")
        retry_wait, _ = self.core_job("RETRYING")
        cancelled, _ = self.core_job("CANCELLED")
        self.c.execute("UPDATE vod_transcode_job SET updated_at=NOW() - INTERVAL 10 MINUTE WHERE id IN (%s,%s)",
                       (stale_pending, cancelled))
        self.expire_lease(expired)
        self.c.execute("UPDATE vod_transcode_job SET next_attempt_at=NOW() - INTERVAL 10 MINUTE WHERE id=%s",
                       (retry_due,))
        self.c.execute("UPDATE vod_transcode_job SET next_attempt_at=NOW() + INTERVAL 10 MINUTE WHERE id=%s",
                       (retry_wait,))
        ids = {r["id"] for r in fencing.due_jobs(self.c, "core", 120)}
        self.assertEqual(ids, {stale_pending, expired, retry_due})


class DlqReplay(Base):
    def test_replay_resets_failed_core_job(self):
        job_id, asset_id = self.core_job("FAILED", asset_status="FAILED", attempts=3, claim_version=4,
                                         error_code="NO_VIDEO_STREAM", last_error="no video stream")

        def message(j, a, t):
            return {"schema_version": 1, "job_id": j, "asset_id": a, "trace_id": t}

        tx = connect(DB, autocommit=True)
        outbox_id = replay_job(tx, job_id, message)
        tx.close()
        row = self.job(job_id)
        self.assertEqual((row["status"], row["attempts"], row["claim_version"], row["error_code"], row["last_error"]),
                         ("PENDING", 0, 5, None, None))
        self.assertEqual(self.asset_status(asset_id), "UPLOADED")
        self.c.execute("SELECT status, payload FROM vod_outbox WHERE id=%s", (outbox_id,))
        out = self.c.fetchone()
        self.assertEqual(out["status"], "PENDING")
        self.assertIn('"origin": "core"', out["payload"])
        # 重放后的任务能被正常领取
        self.assertTrue(fencing.claim(self.c, "core", job_id, "w1", 60))
        tx = connect(DB, autocommit=True)
        with self.assertRaises(ReplayRefused):
            replay_job(tx, job_id, message)  # 现在是 RUNNING，拒绝
        tx.close()
        self.c.execute("SELECT COUNT(*) AS n FROM vod_outbox")
        self.assertEqual(self.c.fetchone()["n"], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
