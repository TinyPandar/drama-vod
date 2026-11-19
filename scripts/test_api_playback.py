#!/usr/bin/env python3
"""不需要 Docker。FastAPI 侧的 Phase 4 改动：回源密钥常量时间比较、启动密钥检查、vod-play 走 Core token、
旧 /keys/{id} 不接受 Core token。需要 Python 3.10+ 和 api/requirements.txt 里的包（没有 fastapi/httpx 时跳过）。"""
import os, sys, unittest

if sys.version_info < (3, 10):
    raise SystemExit("test_api_playback.py needs Python 3.10+")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "api"))
import accounts  # noqa: E402
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
        self.saved = {k: getattr(main, k) for k in ("ORIGIN_SECRET", "VOD_ENV", "get_episode", "can_play",
                                                     "s3_internal", "db", "r")}
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

    # ---------- 路线 C：播放一律经视频云，观众来自 Demo 会话 ----------
    def _core_episode(self, paid):
        main.get_episode = lambda eid: {"id": eid, "is_paid": int(paid), "price_coins": 30, "drama_id": 1, "ep_no": 1,
                                        "asset_id": None, "core_asset_id": 42, "status": "READY"}

    def _stub_side_effects(self):
        class Cur:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def execute(self, *a):
                return 0

            def fetchone(self):
                return None  # 没有下一集

        class Conn:
            def cursor(self):
                return Cur()

            def close(self):
                pass

        class Pipe:
            def __getattr__(self, name):
                return lambda *a, **k: self

        class Redis:
            def pipeline(self):
                return Pipe()

        main.db = lambda: Conn()
        main.r = Redis()

    def _session(self, uid):
        return {"Authorization": "Bearer " + accounts.issue_session(uid, main.DEMO_SESSION_SECRET.encode(), 600)[0]}

    def test_play_returns_core_token_native_url_and_real_encrypted(self):
        self._core_episode(paid=False)
        self._stub_side_effects()
        calls = []

        def token(asset_id, **kw):
            calls.append((asset_id, kw))
            return 200, {"token": "vpt1.p.s", "master_url": "/hls/v1/sig/9/42/7-1/master.m3u8", "expires_at": 9,
                         "encrypted": True, "native_master_url": "/api/v1/native-hls/42/master.m3u8?token=vpt1.p.s"}

        main.vod_client.playback_token = token
        for path in ("/api/episodes/11/play", "/api/episodes/11/vod-play"):
            r = self.client.get(path)  # 免费集，匿名观众
            self.assertEqual(r.status_code, 200, r.text)
            body = r.json()
            self.assertEqual((body["token"], body["encrypted"], body["asset_id"], body["via"]), ("vpt1.p.s", True, 42, "vod-api"))
            self.assertEqual(body["native_master_url"], "/api/v1/native-hls/42/master.m3u8?token=vpt1.p.s")
            for field in ("episode_id", "master_url", "expires_at", "next"):
                self.assertIn(field, body)
        self.assertEqual(calls[0][0], 42)
        self.assertEqual(calls[0][1]["viewer_id"], "anon")

    def test_paid_episode_needs_login_then_unlock_and_core_errors_map(self):
        self._core_episode(paid=True)
        self._stub_side_effects()
        main.vod_client.playback_token = lambda *a, **k: self.fail("must not call Core before unlock")
        main.can_play = lambda uid, ep: uid is not None and ep["is_paid"] and False
        self.assertEqual(self.client.get("/api/episodes/11/play").status_code, 401)
        self.assertEqual(self.client.get("/api/episodes/11/play", params={"user_id": 7}).status_code, 401,
                         "没有会话时 ?user_id= 不再算数")
        self.assertEqual(self.client.get("/api/episodes/11/play", headers=self._session(7)).status_code, 402)
        self.assertEqual(self.client.get("/api/episodes/11/play", params={"user_id": 8}, headers=self._session(7)).status_code,
                         403, "会话与 user_id 不一致")
        main.can_play = lambda uid, ep: uid == 7
        seen = []
        for core_status, expected in ((409, 409), (502, 502), (404, 502), (500, 502)):
            main.vod_client.playback_token = lambda *a, s=core_status, **k: (seen.append(k.get("viewer_id")), (s, {"detail": "x"}))[1]
            r = self.client.get("/api/episodes/11/play", headers=self._session(7))
            self.assertEqual(r.status_code, expected, core_status)
        self.assertEqual(seen[0], "u7")

    def test_legacy_user_id_param_only_with_transition_switch(self):
        self._core_episode(paid=True)
        self._stub_side_effects()
        main.can_play = lambda uid, ep: uid == 7
        main.vod_client.playback_token = lambda *a, **k: (200, {"token": "vpt1.a.b", "master_url": "/m", "expires_at": 1})
        saved = main.DEMO_ALLOW_USER_ID_PARAM
        try:
            main.DEMO_ALLOW_USER_ID_PARAM = True
            self.assertEqual(self.client.get("/api/episodes/11/play", params={"user_id": 7}).status_code, 200)
        finally:
            main.DEMO_ALLOW_USER_ID_PARAM = saved

    def test_unlock_requires_own_session(self):
        self.assertEqual(self.client.post("/api/users/7/unlock", json={"episode_id": 1}).status_code, 401)
        self.assertEqual(self.client.post("/api/users/7/unlock", json={"episode_id": 1}, headers=self._session(8)).status_code, 403)
        forged = {"Authorization": "Bearer " + accounts.issue_session(7, b"other-secret", 600)[0]}
        self.assertEqual(self.client.post("/api/users/7/unlock", json={"episode_id": 1}, headers=forged).status_code, 401)
        self.assertEqual(self.client.get("/api/me").status_code, 401)

    def test_register_validates_input_before_touching_the_database(self):
        main.db = lambda: self.fail("must not reach the database")
        for body in ({"username": "ab", "password": "long-enough"}, {"username": "bad name", "password": "long-enough"},
                     {"username": "alice", "password": "short"}):
            self.assertEqual(self.client.post("/api/auth/register", json=body).status_code, 400, body)


class AccountsTest(unittest.TestCase):
    def test_password_hash_round_trip_and_salt(self):
        h1, h2 = accounts.hash_password("correct horse", iterations=1000), accounts.hash_password("correct horse", iterations=1000)
        self.assertNotEqual(h1, h2, "每次随机盐")
        self.assertTrue(accounts.verify_password("correct horse", h1))
        self.assertFalse(accounts.verify_password("correct horsE", h1))
        self.assertFalse(accounts.verify_password("x", "garbage"))
        self.assertTrue(h1.startswith("pbkdf2_sha256$1000$"))

    def test_session_token_signature_and_expiry(self):
        token, exp = accounts.issue_session(5, b"s3cret", 60, now=1000)
        self.assertEqual(exp, 1060)
        self.assertEqual(accounts.verify_session(token, b"s3cret", now=1059), 5)
        self.assertIsNone(accounts.verify_session(token, b"s3cret", now=1061))
        self.assertIsNone(accounts.verify_session(token, b"other", now=1000))
        self.assertIsNone(accounts.verify_session(token[:-1] + ("0" if token[-1] != "0" else "1"), b"s3cret", now=1000))
        self.assertIsNone(accounts.verify_session("vpt1.x.y", b"s3cret"))
        self.assertIsNone(accounts.verify_session(None, b"s3cret"))
        self.assertEqual(accounts.bearer("Bearer abc"), "abc")
        self.assertEqual(accounts.bearer("bearer  abc "), "abc")
        self.assertEqual(accounts.bearer(None), "")

    def test_username_and_password_rules(self):
        self.assertTrue(accounts.valid_username("viewer_1.a-b"))
        for bad in ("ab", "a" * 33, "有空 格", None, 5):
            self.assertFalse(accounts.valid_username(bad), bad)
        self.assertTrue(accounts.valid_password("12345678"))
        self.assertFalse(accounts.valid_password("1234567"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
