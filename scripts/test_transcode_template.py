#!/usr/bin/env python3
"""Phase 5：Worker 按 job_id 读模板（worker/transcode_template.py）。不需要数据库，用假 cursor。"""
import os, sys, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "worker"))
import transcode_template as tt  # noqa: E402
from retry_policy import NON_RETRYABLE, TEMPLATE_INVALID, JobError  # noqa: E402


class Cursor:
    def __init__(self, row):
        self.row, self.calls = row, []

    def execute(self, sql, params=None):
        self.calls.append((sql, params))

    def fetchone(self):
        return self.row


class TemplateTest(unittest.TestCase):
    def test_default_matches_frozen_ladder(self):
        self.assertEqual(tt.DEFAULT_LADDER, [("720p", 720, 2500), ("540p", 540, 1200), ("360p", 360, 600)])
        self.assertEqual(tt.load(Cursor(None), 7), (tt.DEFAULT_LADDER, 96))

    def test_reads_by_job_id_only(self):
        c = Cursor({"ladder_json": '[["360p",360,500],["720p",720,2000]]', "audio_kbps": 64})
        ladder, audio = tt.load(c, 42)
        self.assertEqual(ladder, [("720p", 720, 2000), ("360p", 360, 500)])  # 按短边从高到低
        self.assertEqual(audio, 64)
        sql, params = c.calls[0]
        self.assertEqual(params, (42,))
        self.assertIn("j.template_id", sql)
        self.assertIn("s.tenant_id = 0 AND s.name = j.profile", sql)

    def test_invalid_template_is_non_retryable(self):
        for bad in ('[["x",360,500]]', '[["360p",361,500]]', "[]", "not json", '[["360p",360]]'):
            with self.assertRaises(JobError) as e:
                tt.parse_ladder(bad)
            self.assertEqual(e.exception.code, TEMPLATE_INVALID, bad)
            self.assertFalse(e.exception.retryable)
        self.assertIn(TEMPLATE_INVALID, NON_RETRYABLE)


if __name__ == "__main__":
    unittest.main(verbosity=2)
