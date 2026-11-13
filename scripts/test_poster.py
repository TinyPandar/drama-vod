#!/usr/bin/env python3
"""不需要 Docker/MySQL。Phase 6 封面：进本地清单并做远端对账、抽帧失败仍提交成功且 poster_key 为空、
key 落在当期前缀、成功事务 SQL；本机有 ffmpeg 时再用真实 ffmpeg 抽一帧。

复用 test_worker_reliability.py 的假数据库 / 假对象存储。
"""
import os, shutil, subprocess, sys, tempfile, threading, unittest

sys.path.insert(0, os.path.dirname(__file__))
from test_worker_reliability import (JOB_ROW, FakeConn, FakeCursor, FakeS3, fake_hls, fencing,  # noqa: E402
                                     metric_value, patched, rp, worker, write)

JPEG = b"\xff\xd8\xff\xe0" + b"0" * 64 + b"\xff\xd9"


def fake_run(poster=JPEG, fail=None):
    """HLS 命令走 fake_hls；封面命令写一张假 JPEG（或抛 fail）。"""
    def run(cmd, timeout=None, stop=None, fail_code=None):
        if "-hls_segment_filename" in cmd:
            return fake_hls(cmd)
        if fail is not None:
            raise fail
        with open(cmd[-1], "wb") as f:
            f.write(poster)
        return ""
    return run


class PosterProcessTest(unittest.TestCase):
    def setUp(self):
        self.s3 = FakeS3()
        self.commits = []

    def run_process(self, job_id, origin="core", row=None, **extra):
        cur = FakeCursor(rows=[dict(row or JOB_ROW)])
        conn = FakeConn(cur)

        def commit_success(c, *a, **k):
            self.commits.append((a, k))
            return True

        names = dict(db=lambda: conn, claim=lambda *a: True, mark_transcoding=lambda *a: 1,
                     renew_lease=lambda *a: True, download_source=lambda b, k, d: write(d),
                     probe=lambda path, stop=None: (720, 1280, 8000, False), run=fake_run(),
                     verify_hls=lambda *a: True, s3c=self.s3, commit_success=commit_success,
                     record_failure=lambda *a, **k: 1, apply_failure=lambda *a: True, HEARTBEAT_SEC=3600,
                     r=type("R", (), {"delete": staticmethod(lambda *a: None)})())
        names.update(extra)
        with patched(**names):
            return worker.process(job_id, "trace", origin)

    def test_poster_is_uploaded_in_job_prefix_before_master_and_committed(self):
        before = metric_value(worker.POSTERS.labels("ok"))
        self.assertEqual(self.run_process(201), "ok")
        prefix = "assets/v1/7/201-2/"
        self.assertIn(prefix + "poster.jpg", self.s3.uploaded)
        self.assertEqual(self.s3.uploaded[-1], prefix + "master.m3u8")  # master 仍最后
        self.assertTrue(all(k.startswith(prefix) for k in self.s3.uploaded))
        (args, kwargs), = self.commits
        self.assertEqual(args[4], prefix)
        self.assertEqual(kwargs["poster_key"], prefix + "poster.jpg")  # 落在当期前缀，不是 assets/v1/7/poster.jpg
        self.assertEqual(self.s3.objects[prefix + "poster.jpg"], len(JPEG))
        self.assertEqual(metric_value(worker.POSTERS.labels("ok")), before + 1)

    def test_poster_is_part_of_remote_manifest(self):
        # 远端少了 poster.jpg：和缺 ts 一样按 STORAGE_ERROR 重试，不提交
        self.s3 = FakeS3(lambda o: {k: v for k, v in o.items() if not k.endswith("poster.jpg")})
        res = self.run_process(202)
        self.assertEqual(res[0], "retry")
        self.assertEqual(self.commits, [])
        # 远端封面大小不对
        self.setUp()
        self.s3 = FakeS3(lambda o: {k: (v + 1 if k.endswith("poster.jpg") else v) for k, v in o.items()})
        self.assertEqual(self.run_process(203)[0], "retry")
        self.assertEqual(self.commits, [])

    def test_extract_failure_still_succeeds_without_poster(self):
        for name, run in (("ffmpeg error", fake_run(fail=rp.JobError(rp.OUTPUT_INVALID, "no frame"))),
                          ("timeout", fake_run(fail=rp.JobError(rp.TIMEOUT, "slow"))),
                          ("empty file", fake_run(poster=b""))):
            self.setUp()
            res = self.run_process(204, run=run)
            self.assertEqual(res, "ok", name)
            (args, kwargs), = self.commits
            self.assertIsNone(kwargs["poster_key"], name)
            self.assertFalse(any(k.endswith("poster.jpg") or "poster" in k for k in self.s3.uploaded), name)

    def test_disabled_writes_no_poster(self):
        self.assertEqual(self.run_process(205, POSTER_ENABLED=False), "ok")
        self.assertIsNone(self.commits[0][1]["poster_key"])
        self.assertFalse(any("poster" in k for k in self.s3.uploaded))

    def test_fence_lost_during_extract_drops(self):
        res = self.run_process(206, run=fake_run(fail=rp.FenceLost("killed")))
        self.assertEqual(res, "drop")
        self.assertEqual(self.commits, [])
        self.assertEqual(self.s3.uploaded, [])

    def test_legacy_has_no_poster(self):
        calls = []
        res = self.run_process(207, origin="legacy", row=dict(JOB_ROW, episode_id=9),
                               extract_poster=lambda *a, **k: calls.append(a) or True)
        self.assertEqual(res, "ok")
        self.assertEqual(calls, [])
        self.assertNotIn("poster_key", self.commits[0][1])
        self.assertFalse(any("poster" in k for k in self.s3.uploaded))


class PosterSqlTest(unittest.TestCase):
    def test_core_success_writes_poster_key_with_ready_and_prefix(self):
        c = FakeCursor(affected=1)
        p = "assets/v1/7/3-5/"
        self.assertTrue(fencing.commit_success(c, "core", 3, 5, 7, p, 1000, 720, 1280, poster_key=p + "poster.jpg"))
        sql, params = next((s, q) for s, q in c.calls if "UPDATE vod_media_asset SET status='READY'" in s)
        self.assertIn("poster_key=%s", sql)
        self.assertEqual(params, (p, 1000, 720, 1280, p + "poster.jpg", 7))

    def test_poster_outside_current_prefix_or_missing_is_null(self):
        p = "assets/v1/7/3-5/"
        for key in (None, "assets/v1/7/poster.jpg", "assets/v1/7/3-4/poster.jpg", p + "x/poster.jpg"):
            c = FakeCursor(affected=1)
            self.assertTrue(fencing.commit_success(c, "core", 3, 5, 7, p, 1, 2, 2, poster_key=key))
            params = next(q for s, q in c.calls if "UPDATE vod_media_asset SET status='READY'" in s)
            self.assertIsNone(params[4], key)

    def test_legacy_sql_unchanged(self):
        c = FakeCursor(affected=1)
        fencing.commit_success(c, "legacy", 3, 5, 7, "assets/7/", 1000, 720, 1280, [], episode_id=9)
        self.assertFalse(any("poster" in s for s, _ in c.calls))


class PosterPositionTest(unittest.TestCase):
    def test_position(self):
        self.assertEqual(worker.poster_position(8000, 1.0), 1.0)
        self.assertEqual(worker.poster_position(600, 1.0), 0.3)   # 比片长短：取中间
        self.assertEqual(worker.poster_position(1000, 1.0), 0.5)  # 正好等于片长也取中间
        self.assertEqual(worker.poster_position(0, 1.0), 0.0)
        self.assertEqual(worker.poster_position(None, 1.0), 0.0)

    def test_cmd_is_argument_array(self):
        cmd = worker.build_poster_cmd("/tmp/a b;rm -rf x", "/tmp/out.jpg", 1.0, 1280)
        self.assertEqual(cmd[0], "ffmpeg")
        self.assertIn("/tmp/a b;rm -rf x", cmd)  # 原样作为一个参数，不经 shell
        self.assertEqual(cmd[cmd.index("-frames:v") + 1], "1")
        self.assertEqual(cmd[-1], "/tmp/out.jpg")


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg not installed")
class RealFfmpegPosterTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.out = os.path.join(self.dir, "out")
        os.makedirs(self.out)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def make(self, name, args):
        path = os.path.join(self.dir, name)
        subprocess.run(["ffmpeg", "-y", "-v", "error"] + args + [path], check=True)
        return path

    def test_extracts_jpeg_from_real_video(self):
        src = self.make("v.mp4", ["-f", "lavfi", "-i", "testsrc=size=720x1280:rate=25:duration=3",
                                  "-c:v", "libx264", "-pix_fmt", "yuv420p"])
        self.assertTrue(worker.extract_poster(src, self.out, 3000, self.dir))
        with open(os.path.join(self.out, "poster.jpg"), "rb") as f:
            data = f.read()
        self.assertTrue(data.startswith(b"\xff\xd8") and len(data) > 1000)
        self.assertFalse(os.path.exists(os.path.join(self.dir, "poster.tmp.jpg")))
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0",
                                os.path.join(self.out, "poster.jpg")], capture_output=True, text=True, check=True)
        self.assertEqual(probe.stdout.strip(), "720,1280")

    def test_short_clip_uses_middle_frame(self):
        src = self.make("short.mp4", ["-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=0.4",
                                      "-c:v", "libx264", "-pix_fmt", "yuv420p"])
        self.assertTrue(worker.extract_poster(src, self.out, 400, self.dir))

    def test_wide_source_is_scaled_down(self):
        src = self.make("wide.mp4", ["-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=25:duration=2",
                                     "-c:v", "libx264", "-pix_fmt", "yuv420p"])
        self.assertTrue(worker.extract_poster(src, self.out, 2000, self.dir))
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0",
                                os.path.join(self.out, "poster.jpg")], capture_output=True, text=True, check=True)
        self.assertEqual(probe.stdout.strip(), "1280,720")

    def test_audio_only_or_garbage_returns_false(self):
        audio = self.make("a.m4a", ["-f", "lavfi", "-i", "sine=duration=2", "-c:a", "aac"])
        self.assertFalse(worker.extract_poster(audio, self.out, 2000, self.dir))
        junk = os.path.join(self.dir, "junk.mp4")
        with open(junk, "wb") as f:
            f.write(os.urandom(4096))
        self.assertFalse(worker.extract_poster(junk, self.out, 2000, self.dir))
        self.assertEqual(os.listdir(self.out), [])

    def test_stop_event_raises_fence_lost(self):
        stop = threading.Event()
        stop.set()
        src = self.make("v2.mp4", ["-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=1",
                                   "-c:v", "libx264", "-pix_fmt", "yuv420p"])
        with patched(run=lambda *a, **k: (_ for _ in ()).throw(rp.FenceLost("lost"))):
            with self.assertRaises(rp.FenceLost):
                worker.extract_poster(src, self.out, 1000, self.dir, stop)


if __name__ == "__main__":
    unittest.main()
