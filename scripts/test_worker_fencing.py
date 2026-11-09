#!/usr/bin/env python3
"""不需要 Docker/MySQL。用假 cursor 检查资产状态更新带 claim_version 围栏。"""
import os, sys, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "worker"))
from fencing import apply_failure, update_asset_status


class FakeCursor:
    """只记录 SQL/参数；affected 模拟 UPDATE 影响行数，still_held 模拟回查围栏结果。"""

    def __init__(self, affected=1, still_held=True):
        self.affected, self.still_held, self.calls = affected, still_held, []

    def execute(self, sql, params=None):
        self.calls.append((sql, params))
        return self.affected

    def fetchone(self):
        return (1,) if self.still_held else None


class FencingTest(unittest.TestCase):
    def test_transcoding_is_fenced_for_core(self):
        c = FakeCursor()
        self.assertEqual(update_asset_status(c, "core", 7, 3, 5, "TRANSCODING"), 1)
        sql, params = c.calls[0]
        self.assertIn("JOIN vod_transcode_job", sql)
        self.assertIn("j.claim_version=%s", sql)
        self.assertIn("UPDATE vod_media_asset", sql)
        self.assertEqual(params, ("TRANSCODING", 7, 3, 5))

    def test_transcoding_is_fenced_for_legacy(self):
        c = FakeCursor()
        self.assertEqual(update_asset_status(c, "legacy", 7, 3, 5, "TRANSCODING"), 1)
        sql, params = c.calls[0]
        self.assertIn("JOIN transcode_job", sql)
        self.assertIn("j.claim_version=%s", sql)
        self.assertIn("UPDATE video_asset", sql)
        self.assertEqual(params, ("TRANSCODING", 7, 3, 5))

    def test_unchanged_status_still_counts_as_holding_fence(self):
        # MySQL 只统计实际变化的行；重试时资产已是 TRANSCODING 不能误判为围栏丢失。
        c = FakeCursor(affected=0, still_held=True)
        self.assertEqual(update_asset_status(c, "core", 7, 3, 5, "TRANSCODING"), 1)

    def test_lost_fence_returns_zero(self):
        c = FakeCursor(affected=0, still_held=False)
        self.assertEqual(update_asset_status(c, "core", 7, 3, 5, "TRANSCODING"), 0)

    def test_final_failure_with_lost_job_fence_does_not_touch_asset(self):
        c = FakeCursor()
        self.assertFalse(apply_failure(c, "core", 7, 3, 5, job_changed=0, final=True))
        self.assertEqual(c.calls, [])  # 不写资产、不投 DLQ/retry

    def test_final_failure_with_fence_writes_fenced_failed(self):
        c = FakeCursor()
        self.assertTrue(apply_failure(c, "core", 7, 3, 5, job_changed=1, final=True))
        sql, params = c.calls[0]
        self.assertIn("j.claim_version=%s", sql)
        self.assertEqual(params, ("FAILED", 7, 3, 5))

    def test_retry_keeps_asset_untouched(self):
        c = FakeCursor()
        self.assertTrue(apply_failure(c, "core", 7, 3, 5, job_changed=1, final=False))
        self.assertEqual(c.calls, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
