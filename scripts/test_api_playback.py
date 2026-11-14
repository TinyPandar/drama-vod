#!/usr/bin/env python3
"""不需要 Docker。FastAPI 侧的 Phase 4 改动：回源密钥常量时间比较、启动密钥检查、vod-play 走 Core token、
旧 /keys/{id} 不接受 Core token。需要 Python 3.10+ 和 api/requirements.txt 里的包（没有 fastapi/httpx 时跳过）。"""
import os, sys, unittest

if sys.version_info < (3, 10):
    raise SystemExit("test_api_playback.py needs Python 3.10+")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "api"))
import secret_guard  # noqa: E402

try:
    from fastapi.testclient import TestClient
    import main  # 只建客户端，不连网络；TestClient 不用 with，不跑 startup
except ImportError as e:  # pragma: no cover
    TestClient = main = None
    MISSING = str(e)

STRONG = "0123456789abcdef0123456789abcdef"


class SecretGuardTest(unittest.TestCase):
    def test_non_dev_rejects_missing_dev_and_short(self):
        for weak in ("", "dev", "DEV", "short", None):
            with self.assertRaises(RuntimeError) as ctx:
                secret_guard.check_secrets({"ORIGIN_SECRET": weak, "HLS_SECRET": STRONG}, "prod", 32)
            self.assertIn("ORIGIN_SECRET", str(ctx.exception))
            self.assertNotIn(STRONG, str(ctx.exception))
        self.assertEqual(secret_guard.check_secrets({"A": STRONG, "B": STRONG[::-1]}, "prod", 32), [])
        self.assertEqual(secret_guard.check_secrets({"A": "abcdefgh"}, "staging", 8), [])

    def test_dev_only_warns_and_falls_back(self):
        warned = []
        self.assertEqual(secret_guard.check_secrets({"ORIGIN_SECRET": ""}, "dev", 32, warn=warned.append),
                         ["ORIGIN_SECRET"])
        self.assertEqual(len(warned), 1)
        self.assertEqual(secret_guard.dev_fallback("", "dev"), "dev")
        self.assertEqual(secret_guard.dev_fallback("", "prod"), "")
        self.assertEqual(secret_guard.dev_fallback("x", "dev"), "x")
        self.assertTrue(secret_guard.is_dev(None) and secret_guard.is_dev(" Dev ") and not secret_guard.is_dev("prod"))


@unittest.skipIf(main is None, "fastapi/httpx not installed")
class ApiTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(main.app)
        self.saved = {k: getattr(main, k) for k in ("ORIGIN_SECRET", "VOD_ENV", "get_episode", "vod_link", "can_play",
                                                     "s3_internal")}
        self.saved_client = main.vod_client.playback_token

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(main, k, v)
        main.vod_client.playback_token = self.saved_client

    def test_internal_origin_requires_secret(self):
        main.ORIGIN_SECRET = "origin-secret-value"
        for headers in ({}, {"X-Origin-Secret": "wrong"}, {"X-Origin-Secret": "origin-secret-valuE"}):
            r = self.client.get("/internal/origin/v1/5/9-1/master.m3u8", headers=headers)
            self.assertEqual(r.status_code, 403, headers)

        class Body:
            def iter_chunks(self, n):
                yield b"#EXTM3U\n"

            def close(self):
                pass

        seen = []

        class S3:
            def get_object(self, Bucket, Key):
                seen.append(Key)
                return {"Body": Body(), "ContentType": "application/vnd.apple.mpegurl"}

        main.s3_internal = S3()
        r = self.client.get("/internal/origin/v1/5/9-1/master.m3u8", headers={"X-Origin-Secret": "origin-secret-value"})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(seen, ["assets/v1/5/9-1/master.m3u8"])  # gen 路径按多级对象键回源
        self.assertNotIn("content-length", r.headers)  # 对象大小未知时不编造

    def test_internal_origin_sends_content_length_when_size_known(self):
        """Nginx 的 range 过滤要求上游带 Content-Length，否则缓存命中后的 Range 只回 200。"""
        main.ORIGIN_SECRET = "origin-secret-value"
        payload = bytes(range(256)) * 1000  # 256000 字节，跨多个 64 KiB 块

        class Body:
            def iter_chunks(self, n):
                for i in range(0, len(payload), n):
                    yield payload[i:i + n]

            def close(self):
                pass

        class S3:
            def get_object(self, Bucket, Key):
                return {"Body": Body(), "ContentType": "video/mp2t", "ContentLength": len(payload)}

        main.s3_internal = S3()
        r = self.client.get("/internal/origin/v1/5/9-1/720p/seg_00001.ts", headers={"X-Origin-Secret": "origin-secret-value"})
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.headers["content-length"], str(len(payload)))
        self.assertNotIn("transfer-encoding", r.headers)
        self.assertEqual(r.content, payload)

    def test_origin_check_uses_constant_time_compare(self):
        src = open(main.__file__, encoding="utf-8").read()
        handler = src.split("def internal_origin", 1)[1].split("\n\n\n", 1)[0]
        self.assertIn("hmac.compare_digest", handler)
        self.assertNotIn("!= ORIGIN_SECRET", handler)

    def test_startup_check_refuses_weak_secrets_outside_dev(self):
        main.VOD_ENV, main.ORIGIN_SECRET = "prod", "dev"
        with self.assertRaises(RuntimeError):
            main.check_secrets()
        main.VOD_ENV = "dev"
        main.check_secrets()  # dev 只警告

    def test_legacy_keys_reject_core_token(self):
        core = "vpt1.eyJ0IjoxLCJhIjo1LCJleHAiOjQxMDI0NDQ4MDB9.c2ln"
        for kw in ({"headers": {"Authorization": "Bearer " + core}}, {"params": {"token": core}}):
            self.assertEqual(self.client.get("/keys/5", **kw).status_code, 401)

    def test_vod_play_returns_core_token_and_real_encrypted(self):
        main.get_episode = lambda eid: {"id": eid, "is_paid": 0, "drama_id": 1, "ep_no": 1, "asset_id": None,
                                        "status": "DRAFT"}
        main.vod_link = lambda eid: {"vod_asset_id": 42, "vod_session_id": 3}
        main.can_play = lambda uid, ep: True
        calls = []

        def token(asset_id, **kw):
            calls.append(asset_id)
            return 200, {"token": "vpt1.p.s", "master_url": "/hls/v1/sig/9/42/7-1/master.m3u8", "expires_at": 9,
                         "encrypted": True}

        main.vod_client.playback_token = token
        r = self.client.get("/api/episodes/11/vod-play", params={"user_id": 7})
        self.assertEqual(r.status_code, 200, r.text)
        body = r.json()
        self.assertEqual(calls, [42])
        self.assertEqual((body["token"], body["encrypted"], body["asset_id"], body["via"]), ("vpt1.p.s", True, 42, "vod-api"))
        self.assertEqual(body["master_url"], "/hls/v1/sig/9/42/7-1/master.m3u8")
        for field in ("episode_id", "expires_at", "next"):
            self.assertIn(field, body)

    def test_vod_play_unlock_still_decided_by_demo_and_core_errors_map(self):
        main.get_episode = lambda eid: {"id": eid, "is_paid": 1, "drama_id": 1, "ep_no": 1, "asset_id": None,
                                        "status": "DRAFT"}
        main.vod_link = lambda eid: {"vod_asset_id": 42, "vod_session_id": 3}
        main.can_play = lambda uid, ep: False
        main.vod_client.playback_token = lambda *a, **k: self.fail("must not call Core before unlock")
        self.assertEqual(self.client.get("/api/episodes/11/vod-play", params={"user_id": 7}).status_code, 402)
        main.can_play = lambda uid, ep: True
        for core_status, expected in ((409, 409), (502, 502), (404, 502), (500, 502)):
            main.vod_client.playback_token = lambda *a, s=core_status, **k: (s, {"detail": "x"})
            r = self.client.get("/api/episodes/11/vod-play", params={"user_id": 7})
            self.assertEqual(r.status_code, expected, core_status)


if __name__ == "__main__":
    unittest.main(verbosity=2)
