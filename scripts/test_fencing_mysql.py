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
import output_gc  # noqa: E402
import output_prefix  # noqa: E402
import transcode_template  # noqa: E402
from dlq import ReplayRefused, replay_job  # noqa: E402
from key_provider import PlaintextKeyStore  # noqa: E402

DB = os.environ.get("FENCE_TEST_DB", "vod_fence_test")
if not re.fullmatch(r"[A-Za-z0-9_]+", DB):
    raise SystemExit("FENCE_TEST_DB must be [A-Za-z0-9_]+")

# 与 vod-api JobService.cancel 的条件 UPDATE 保持一致
CANCEL_SQL = ("UPDATE vod_transcode_job SET status='CANCELLED', claim_version=claim_version+1, locked_until=NULL, "
              "next_attempt_at=NULL, updated_at=CURRENT_TIMESTAMP "
              "WHERE id=%s AND tenant_id=%s AND status IN ('PENDING','RETRYING','RUNNING')")
CANCEL_ASSET_SQL = "UPDATE vod_media_asset SET status='CANCELLED' WHERE id=%s AND tenant_id=%s AND status <> 'READY'"
# 与 vod-api TranscodeService.reset 保持一致（Phase 5 重新转码 / 重试复用同一行）
RESET_SQL = ("UPDATE vod_transcode_job SET status='PENDING', claim_version=claim_version+1, attempts=0, error_code=NULL, "
             "last_error=NULL, locked_until=NULL, worker_id=NULL, next_attempt_at=NULL, template_id=%s, "
             "updated_at=CURRENT_TIMESTAMP WHERE id=%s AND status=%s")
CANCEL_PREFIX_SQL = ("UPDATE vod_output_prefix SET status='GARBAGE', updated_at=CURRENT_TIMESTAMP "
                     "WHERE job_id=%s AND status='WRITING'")


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
                  "asset_rendition", "episode", "drama", "vod_output_prefix", "vod_content_key", "content_key"):
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
        if origin == "core":  # 真实流程里上传前一定先记 WRITING；成功事务只激活 WRITING 的前缀
            output_prefix.insert_writing(self.c, asset_id, job_id, new, "p/")
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
        output_prefix.insert_writing(self.c, asset_id, job_id, v, "p/")
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


class TranscodingLockOrder(Base):
    """Phase 3 遗留第 9 项：TRANSCODING 显式事务先锁任务行，与取消事务（任务 → 资产）同序，交错时只等待、不死锁。"""

    def test_cancel_waits_for_transcoding_transaction(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w1", 60)
        v = self.job(job_id)["claim_version"]
        worker_conn = connect(DB)
        wc = worker_conn.cursor()
        worker_conn.begin()
        wc.execute("SELECT claim_version, status FROM vod_transcode_job WHERE id=%s FOR UPDATE", (job_id,))
        result = {}

        def cancel():
            other = connect(DB, autocommit=False)
            try:
                with other.cursor() as oc:
                    other.begin()
                    result["job"] = oc.execute(CANCEL_SQL, (job_id, 1))   # 等 Worker 释放任务行锁
                    oc.execute(CANCEL_ASSET_SQL, (asset_id, 1))
                    oc.execute(CANCEL_PREFIX_SQL, (job_id,))
                other.commit()
            except Exception as e:  # 不应出现死锁
                result["error"] = repr(e)
            finally:
                other.close()

        t = threading.Thread(target=cancel)
        t.start()
        time.sleep(0.5)
        self.assertTrue(t.is_alive())  # 被任务行锁挡住
        wc.execute("UPDATE vod_media_asset SET status='TRANSCODING' WHERE id=%s", (asset_id,))
        worker_conn.commit()
        t.join(10)
        worker_conn.close()
        self.assertNotIn("error", result)
        self.assertEqual(result["job"], 1)
        self.assertEqual(self.job(job_id)["status"], "CANCELLED")
        self.assertEqual(self.asset_status(asset_id), "CANCELLED")
        # 之后 Worker 的围栏已丢
        self.assertEqual(fencing.mark_transcoding(self.conn, self.c, "core", asset_id, job_id, v), 0)
        self.assertEqual(self.asset_status(asset_id), "CANCELLED")

    def test_transcoding_waits_for_cancel_then_sees_lost_fence(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w1", 60)
        v = self.job(job_id)["claim_version"]
        canceller = connect(DB, autocommit=False)
        cc = canceller.cursor()
        canceller.begin()
        self.assertEqual(cc.execute(CANCEL_SQL, (job_id, 1)), 1)   # 取消事务先拿到任务行锁
        result = {}

        def transcoding():
            wconn = connect(DB)
            try:
                result["n"] = fencing.mark_transcoding(wconn, wconn.cursor(), "core", asset_id, job_id, v)
            except Exception as e:
                result["error"] = repr(e)
            finally:
                wconn.close()

        t = threading.Thread(target=transcoding)
        t.start()
        time.sleep(0.5)
        self.assertTrue(t.is_alive())  # FOR UPDATE 在等任务行
        cc.execute(CANCEL_ASSET_SQL, (asset_id, 1))
        canceller.commit()
        canceller.close()
        t.join(10)
        self.assertNotIn("error", result)
        self.assertEqual(result["n"], 0)
        self.assertEqual(self.asset_status(asset_id), "CANCELLED")

    def test_mark_transcoding_happy_path_and_same_status(self):
        job_id, asset_id = self.core_job(asset_status="TRANSCODING")  # 重试时资产已是 TRANSCODING
        fencing.claim(self.c, "core", job_id, "w1", 60)
        v = self.job(job_id)["claim_version"]
        self.assertEqual(fencing.mark_transcoding(self.conn, self.c, "core", asset_id, job_id, v), 1)
        self.assertEqual(fencing.mark_transcoding(self.conn, self.c, "core", asset_id, job_id, v - 1), 0)
        legacy_job, legacy_asset, _ = self.legacy_job()
        fencing.claim(self.c, "legacy", legacy_job, "w1", 60)
        lv = self.job(legacy_job, "transcode_job")["claim_version"]
        self.assertEqual(fencing.mark_transcoding(self.conn, self.c, "legacy", legacy_asset, legacy_job, lv), 1)
        self.assertEqual(self.asset_status(legacy_asset, "video_asset"), "TRANSCODING")


class OutputPrefixLifecycle(Base):
    """Phase 4 第 3、4 项：任务专属前缀、成功事务切换、取消/替换后的垃圾判定与删前复核。"""

    def prefixes(self, asset_id):
        self.c.execute("SELECT prefix, status FROM vod_output_prefix WHERE asset_id=%s ORDER BY id", (asset_id,))
        return {r["prefix"]: r["status"] for r in self.c.fetchall()}

    def test_two_claims_only_one_active_and_stale_writer_becomes_garbage(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "old", 60)
        old = self.job(job_id)["claim_version"]
        old_prefix = f"assets/v1/{asset_id}/{job_id}-{old}/"
        output_prefix.insert_writing(self.c, asset_id, job_id, old, old_prefix)
        output_prefix.insert_writing(self.c, asset_id, job_id, old, old_prefix)  # 幂等
        self.expire_lease(job_id)
        fencing.claim(self.c, "core", job_id, "new", 60)
        new = old + 1
        new_prefix = f"assets/v1/{asset_id}/{job_id}-{new}/"
        output_prefix.insert_writing(self.c, asset_id, job_id, new, new_prefix)
        # 旧 Worker 提交失败，新 Worker 提交成功
        self.conn.begin()
        self.assertFalse(fencing.commit_success(self.c, "core", job_id, old, asset_id, old_prefix, 1, 2, 2))
        self.conn.rollback()
        self.conn.begin()
        self.assertTrue(fencing.commit_success(self.c, "core", job_id, new, asset_id, new_prefix, 1, 2, 2))
        self.conn.commit()
        self.assertEqual(self.prefixes(asset_id), {old_prefix: "GARBAGE", new_prefix: "ACTIVE"})
        self.c.execute("SELECT hls_prefix FROM vod_media_asset WHERE id=%s", (asset_id,))
        self.assertEqual(self.c.fetchone()["hls_prefix"], new_prefix)
        output_prefix.discard(self.c, new_prefix)  # discard 不会降级 ACTIVE
        self.assertEqual(self.prefixes(asset_id)[new_prefix], "ACTIVE")

    def test_garbage_prefix_cannot_be_reactivated(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w", 60)
        v = self.job(job_id)["claim_version"]
        p = f"assets/v1/{asset_id}/{job_id}-{v}/"
        output_prefix.insert_writing(self.c, asset_id, job_id, v, p)
        output_prefix.discard(self.c, p)                       # 已判为垃圾（可能正在被删）
        self.conn.begin()
        self.assertFalse(fencing.commit_success(self.c, "core", job_id, v, asset_id, p, 1, 2, 2))
        self.conn.rollback()
        self.assertEqual(self.job(job_id)["status"], "RUNNING")  # 任务 SUCCEEDED 一起回滚
        self.c.execute("SELECT status, hls_prefix FROM vod_media_asset WHERE id=%s", (asset_id,))
        row = self.c.fetchone()
        self.assertNotEqual(row["hls_prefix"], p)
        self.assertEqual(self.prefixes(asset_id)[p], "GARBAGE")

    def test_promote_stale_writing(self):
        running, a1 = self.core_job()
        fencing.claim(self.c, "core", running, "w", 60)
        v = self.job(running)["claim_version"]
        output_prefix.insert_writing(self.c, a1, running, v, f"assets/v1/{a1}/{running}-{v}/")        # 在跑：保留
        output_prefix.insert_writing(self.c, a1, running, v - 1, f"assets/v1/{a1}/{running}-{v - 1}/")  # 被接手：垃圾
        failed, a2 = self.core_job("FAILED", "FAILED")
        output_prefix.insert_writing(self.c, a2, failed, 0, f"assets/v1/{a2}/{failed}-0/")              # 终态：垃圾
        output_prefix.insert_writing(self.c, a2, 999999, 1, f"assets/v1/{a2}/999999-1/")                  # 任务不存在：垃圾
        output_gc.promote_stale_writing(self.c)
        self.assertEqual(self.prefixes(a1), {f"assets/v1/{a1}/{running}-{v}/": "WRITING",
                                             f"assets/v1/{a1}/{running}-{v - 1}/": "GARBAGE"})
        self.assertEqual(set(self.prefixes(a2).values()), {"GARBAGE"})

    def test_cancel_marks_writing_garbage_and_gc_respects_grace_and_current_prefix(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w", 60)
        v = self.job(job_id)["claim_version"]
        p = f"assets/v1/{asset_id}/{job_id}-{v}/"
        output_prefix.insert_writing(self.c, asset_id, job_id, v, p)
        self.c.execute(CANCEL_SQL, (job_id, 1))
        self.c.execute(CANCEL_PREFIX_SQL, (job_id,))
        self.assertEqual(self.prefixes(asset_id)[p], "GARBAGE")
        self.assertEqual(output_gc.due_garbage(self.c, 3600, 10), [])           # 宽限期内不删
        self.c.execute("UPDATE vod_output_prefix SET updated_at=NOW() - INTERVAL 2 HOUR WHERE prefix=%s", (p,))
        due = output_gc.due_garbage(self.c, 3600, 10)
        self.assertEqual([r["prefix"] for r in due], [p])
        self.assertTrue(output_gc.still_deletable(self.c, due[0]))
        # 万一资产当前 hls_prefix 就是它：不删
        self.c.execute("UPDATE vod_media_asset SET hls_prefix=%s WHERE id=%s", (p, asset_id))
        self.assertFalse(output_gc.still_deletable(self.c, due[0]))
        self.c.execute("UPDATE vod_media_asset SET hls_prefix=NULL WHERE id=%s", (asset_id,))

        class S3:
            def __init__(self):
                self.keys = {p + "master.m3u8", p + "720p/index.m3u8", p + "720p/seg_00000.ts"}

            def list_objects_v2(self, Bucket, Prefix, MaxKeys):
                return {"Contents": [{"Key": k} for k in sorted(self.keys) if k.startswith(Prefix)][:MaxKeys]}

            def delete_objects(self, Bucket, Delete):
                self.keys -= {o["Key"] for o in Delete["Objects"]}
                return {}

        s3 = S3()
        self.assertEqual(output_gc.gc_pass(self.c, s3, "vod-hls", 3600, 10, 2), (1, 3, 0))
        self.assertEqual(s3.keys, set())
        self.assertEqual(self.prefixes(asset_id)[p], "DELETED")
        self.assertEqual(output_gc.gc_pass(self.c, s3, "vod-hls", 3600, 10, 2), (0, 0, 0))


class GcLockOrder(Base):
    """审阅发现的反向加锁：清理线程不能先锁前缀再去锁任务。取消/成功事务都是任务 → （资产）→ 前缀，
    这里让它们拿到任务行锁后、改前缀之前停一下，期间跑一轮 promote_stale_writing，两边都不能报死锁。"""

    class PauseBeforePrefix:
        def __init__(self, c, seconds):
            self.c, self.seconds, self.paused = c, seconds, False

        def execute(self, sql, params=None):
            if "vod_output_prefix" in sql and not self.paused:
                self.paused = True
                time.sleep(self.seconds)
            return self.c.execute(sql, params)

        def fetchone(self):
            return self.c.fetchone()

    def stale_and_current(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "old", 60)
        self.expire_lease(job_id)
        fencing.claim(self.c, "core", job_id, "new", 60)
        v = self.job(job_id)["claim_version"]
        old_p, cur_p = f"assets/v1/{asset_id}/{job_id}-{v - 1}/", f"assets/v1/{asset_id}/{job_id}-{v}/"
        output_prefix.insert_writing(self.c, asset_id, job_id, v - 1, old_p)   # 过时的写入方：清理要处理它
        output_prefix.insert_writing(self.c, asset_id, job_id, v, cur_p)
        return job_id, asset_id, v, old_p, cur_p

    def run_gc_during(self, tx_body):
        """tx_body(cursor) 在显式事务里执行；清理在它拿到任务行锁之后启动。返回 (事务异常, 清理异常)。"""
        errors = {}

        def gc():
            time.sleep(0.3)
            conn = connect(DB)
            try:
                with conn.cursor() as gc_c:
                    output_gc.promote_stale_writing(gc_c)
            except Exception as e:
                errors["gc"] = repr(e)
            finally:
                conn.close()

        t = threading.Thread(target=gc)
        tx = connect(DB, autocommit=False)
        try:
            t.start()
            tx.begin()
            tx_body(self.PauseBeforePrefix(tx.cursor(), 1.0))
            tx.commit()
        except Exception as e:
            errors["tx"] = repr(e)
            tx.rollback()
        finally:
            tx.close()
            t.join(15)
        return errors.get("tx"), errors.get("gc")

    def prefix_status(self, prefix):
        self.c.execute("SELECT status FROM vod_output_prefix WHERE prefix=%s", (prefix,))
        return self.c.fetchone()["status"]

    def test_gc_and_cancel_do_not_deadlock(self):
        job_id, asset_id, v, old_p, cur_p = self.stale_and_current()

        def cancel(c):
            c.execute(CANCEL_SQL, (job_id, 1))
            c.execute(CANCEL_ASSET_SQL, (asset_id, 1))
            c.execute(CANCEL_PREFIX_SQL, (job_id,))

        tx_err, gc_err = self.run_gc_during(cancel)
        self.assertIsNone(tx_err)
        self.assertIsNone(gc_err)
        self.assertEqual(self.job(job_id)["status"], "CANCELLED")
        self.assertEqual((self.prefix_status(old_p), self.prefix_status(cur_p)), ("GARBAGE", "GARBAGE"))

    def test_gc_and_success_commit_do_not_deadlock(self):
        job_id, asset_id, v, old_p, cur_p = self.stale_and_current()
        result = {}

        def success(c):
            result["ok"] = fencing.commit_success(c, "core", job_id, v, asset_id, cur_p, 1, 2, 2)

        tx_err, gc_err = self.run_gc_during(success)
        self.assertIsNone(tx_err)
        self.assertIsNone(gc_err)
        self.assertTrue(result["ok"])
        self.assertEqual(self.job(job_id)["status"], "SUCCEEDED")
        self.assertEqual((self.prefix_status(old_p), self.prefix_status(cur_p)), ("GARBAGE", "ACTIVE"))


class Phase5Retranscode(Base):
    """Phase 5：重新转码复用同一行（claim_version+1、新前缀），READY 资产在重转期间保持可播；
    成功/失败事件与任务终态同一事务写进 vod_webhook_event，不进 vod_outbox；Worker 按 job_id 读模板。"""

    def setUp(self):
        super().setUp()
        self.c.execute("DELETE FROM vod_webhook_event")
        self.c.execute("DELETE FROM vod_transcode_template WHERE tenant_id <> 0")

    def events(self, job_id):
        self.c.execute("SELECT event_type, claim_version, error_code, status FROM vod_webhook_event WHERE job_id=%s ORDER BY id",
                       (job_id,))
        return self.c.fetchall()

    def run_success(self, job_id, asset_id):
        self.assertTrue(fencing.claim(self.c, "core", job_id, "w", 60))
        v = self.job(job_id)["claim_version"]
        self.assertEqual(fencing.mark_transcoding(self.conn, self.c, "core", asset_id, job_id, v), 1)
        prefix = f"assets/v1/{asset_id}/{job_id}-{v}/"
        output_prefix.insert_writing(self.c, asset_id, job_id, v, prefix)
        self.conn.begin()
        self.assertTrue(fencing.commit_success(self.c, "core", job_id, v, asset_id, prefix, 1000, 720, 1280))
        self.conn.commit()
        return prefix

    def test_retranscode_reuses_row_and_switches_prefix_atomically(self):
        job_id, asset_id = self.core_job()
        p1 = self.run_success(job_id, asset_id)
        self.assertEqual([e["event_type"] for e in self.events(job_id)], ["transcode.succeeded"])
        v1 = self.job(job_id)["claim_version"]
        self.assertEqual(self.c.execute(RESET_SQL, (None, job_id, "SUCCEEDED")), 1)
        self.assertEqual(self.job(job_id)["claim_version"], v1 + 1)
        # 领取后 TRANSCODING 不会把 READY 资产改掉，旧版本继续可播
        self.assertTrue(fencing.claim(self.c, "core", job_id, "w2", 60))
        v2 = self.job(job_id)["claim_version"]
        self.assertEqual(fencing.mark_transcoding(self.conn, self.c, "core", asset_id, job_id, v2), 1)
        self.assertEqual(self.asset_status(asset_id), "READY")
        p2 = f"assets/v1/{asset_id}/{job_id}-{v2}/"
        self.assertNotEqual(p1, p2)
        output_prefix.insert_writing(self.c, asset_id, job_id, v2, p2)
        self.conn.begin()
        self.assertTrue(fencing.commit_success(self.c, "core", job_id, v2, asset_id, p2, 1000, 720, 1280))
        self.conn.commit()
        self.c.execute("SELECT prefix, status FROM vod_output_prefix WHERE asset_id=%s", (asset_id,))
        self.assertEqual({r["prefix"]: r["status"] for r in self.c.fetchall()}, {p1: "GARBAGE", p2: "ACTIVE"})
        self.c.execute("SELECT COUNT(*) AS n FROM vod_transcode_job WHERE asset_id=%s", (asset_id,))
        self.assertEqual(self.c.fetchone()["n"], 1)
        ev = self.events(job_id)
        self.assertEqual([e["event_type"] for e in ev], ["transcode.succeeded", "transcode.succeeded"])
        self.assertEqual(ev[1]["claim_version"], v2)
        self.c.execute("SELECT COUNT(*) AS n FROM vod_outbox")
        self.assertEqual(self.c.fetchone()["n"], 0)

    def test_failed_retranscode_keeps_ready_asset_and_records_failed_event(self):
        job_id, asset_id = self.core_job()
        p1 = self.run_success(job_id, asset_id)
        self.c.execute(RESET_SQL, (None, job_id, "SUCCEEDED"))
        fencing.claim(self.c, "core", job_id, "w2", 60)
        v = self.job(job_id)["claim_version"]
        self.conn.begin()
        changed = fencing.record_failure(self.c, "core", job_id, v, True, "no video", "NO_VIDEO_STREAM")
        self.assertTrue(fencing.apply_failure(self.c, "core", asset_id, job_id, v, changed, True))
        self.conn.commit()
        self.assertEqual(self.asset_status(asset_id), "READY")
        self.c.execute("SELECT hls_prefix FROM vod_media_asset WHERE id=%s", (asset_id,))
        self.assertEqual(self.c.fetchone()["hls_prefix"], p1)
        failed = self.events(job_id)[-1]
        self.assertEqual((failed["event_type"], failed["error_code"], failed["status"]),
                         ("transcode.failed", "NO_VIDEO_STREAM", "PENDING"))
        # 非 READY 资产照旧变 FAILED；重试（RETRYING）不记事件
        j2, a2 = self.core_job()
        fencing.claim(self.c, "core", j2, "w", 60)
        v2 = self.job(j2)["claim_version"]
        self.conn.begin()
        ch = fencing.record_failure(self.c, "core", j2, v2, False, "boom", "STORAGE_ERROR", 1000)
        fencing.apply_failure(self.c, "core", a2, j2, v2, ch, False)
        self.conn.commit()
        self.assertEqual(self.events(j2), ())
        self.c.execute(RESET_SQL, (None, j2, "RETRYING"))  # 只是为了能再领一次
        fencing.claim(self.c, "core", j2, "w", 60)
        v3 = self.job(j2)["claim_version"]
        self.conn.begin()
        ch = fencing.record_failure(self.c, "core", j2, v3, True, "bad", "INPUT_INVALID")
        fencing.apply_failure(self.c, "core", a2, j2, v3, ch, True)
        self.conn.commit()
        self.assertEqual(self.asset_status(a2), "FAILED")
        self.assertEqual([e["event_type"] for e in self.events(j2)], ["transcode.failed"])

    def test_lost_fence_writes_no_event(self):
        job_id, asset_id = self.core_job()
        fencing.claim(self.c, "core", job_id, "w", 60)
        v = self.job(job_id)["claim_version"]
        self.c.execute(CANCEL_SQL, (job_id, 1))
        self.conn.begin()
        self.assertFalse(fencing.commit_success(self.c, "core", job_id, v, asset_id, f"assets/v1/{asset_id}/{job_id}-{v}/", 1, 2, 2))
        self.conn.rollback()
        self.assertEqual(self.events(job_id), ())

    def test_worker_reads_template_by_job_id(self):
        job_id, _ = self.core_job()
        ladder, audio = transcode_template.load(self.c, job_id)
        self.assertEqual(ladder, transcode_template.DEFAULT_LADDER)
        self.assertEqual(audio, 96)
        self.c.execute("INSERT INTO vod_transcode_template(tenant_id, name, ladder_json, audio_kbps) "
                       "VALUES (1, 'mobile', '[[\"360p\",360,500],[\"540p\",540,900]]', 64)")
        self.c.execute("UPDATE vod_transcode_job SET template_id=%s, profile='mobile' WHERE id=%s", (self.c.lastrowid, job_id))
        ladder, audio = transcode_template.load(self.c, job_id)
        self.assertEqual(ladder, [("540p", 540, 900), ("360p", 360, 500)])
        self.assertEqual(audio, 64)


class Phase6Poster(Base):
    """Phase 6：poster_key 与 hls_prefix / READY / 前缀 ACTIVE 同一事务写入；重转后跟着新前缀走，旧前缀进 GARBAGE；
    围栏丢失时什么都不写；没有封面写 NULL。"""

    def asset_cols(self, asset_id):
        self.c.execute("SELECT status, hls_prefix, poster_key FROM vod_media_asset WHERE id=%s", (asset_id,))
        return self.c.fetchone()

    def succeed(self, job_id, asset_id, poster=True):
        self.assertTrue(fencing.claim(self.c, "core", job_id, "w", 60))
        v = self.job(job_id)["claim_version"]
        self.assertEqual(fencing.mark_transcoding(self.conn, self.c, "core", asset_id, job_id, v), 1)
        prefix = f"assets/v1/{asset_id}/{job_id}-{v}/"
        output_prefix.insert_writing(self.c, asset_id, job_id, v, prefix)
        self.conn.begin()
        self.assertTrue(fencing.commit_success(self.c, "core", job_id, v, asset_id, prefix, 1000, 720, 1280,
                                               poster_key=prefix + "poster.jpg" if poster else None))
        self.conn.commit()
        return prefix

    def test_poster_follows_active_prefix(self):
        job_id, asset_id = self.core_job()
        p1 = self.succeed(job_id, asset_id)
        self.assertEqual(self.asset_cols(asset_id), {"status": "READY", "hls_prefix": p1, "poster_key": p1 + "poster.jpg"})
        self.c.execute(RESET_SQL, (None, job_id, "SUCCEEDED"))
        p2 = self.succeed(job_id, asset_id)
        self.assertEqual(self.asset_cols(asset_id)["poster_key"], p2 + "poster.jpg")
        self.c.execute("SELECT prefix, status FROM vod_output_prefix WHERE asset_id=%s", (asset_id,))
        self.assertEqual({r["prefix"]: r["status"] for r in self.c.fetchall()}, {p1: "GARBAGE", p2: "ACTIVE"})
        # 第三次没出封面：写 NULL，不保留指向旧前缀（会被 GC）的键
        self.c.execute(RESET_SQL, (None, job_id, "SUCCEEDED"))
        p3 = self.succeed(job_id, asset_id, poster=False)
        self.assertEqual(self.asset_cols(asset_id), {"status": "READY", "hls_prefix": p3, "poster_key": None})

    def test_lost_fence_writes_no_poster(self):
        job_id, asset_id = self.core_job()
        self.assertTrue(fencing.claim(self.c, "core", job_id, "w", 60))
        v = self.job(job_id)["claim_version"]
        self.c.execute("UPDATE vod_transcode_job SET claim_version=claim_version+1 WHERE id=%s", (job_id,))
        p = f"assets/v1/{asset_id}/{job_id}-{v}/"
        self.conn.begin()
        self.assertFalse(fencing.commit_success(self.c, "core", job_id, v, asset_id, p, 1, 2, 2, poster_key=p + "poster.jpg"))
        self.conn.rollback()
        self.assertIsNone(self.asset_cols(asset_id)["poster_key"])


class ContentKeys(Base):
    def test_core_and_legacy_keys_do_not_collide(self):
        store = PlaintextKeyStore()
        core = store.get_or_create(self.c, "core", 5)
        legacy = store.get_or_create(self.c, "legacy", 5)   # 同一个数字 id，两套资产
        self.assertNotEqual(core["key_hex"], legacy["key_hex"])
        self.assertEqual(store.get_or_create(self.c, "core", 5), core)  # 重试复用同一把 key
        self.c.execute("SELECT COUNT(*) AS n FROM vod_content_key")
        self.assertEqual(self.c.fetchone()["n"], 1)
        self.c.execute("SELECT COUNT(*) AS n FROM content_key")
        self.assertEqual(self.c.fetchone()["n"], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
