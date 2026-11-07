#!/usr/bin/env python3
"""不需要 Docker。检查 HLS 产物校验会拒绝半成品。"""
import os, sys, tempfile
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "worker"))
from hls_verify import verify_hls

def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w", encoding="utf-8").write(text)

def test_ok():
    with tempfile.TemporaryDirectory() as root:
        write(os.path.join(root, "720p", "index.m3u8"), "#EXTM3U\nseg_00000.ts\n")
        write(os.path.join(root, "720p", "seg_00000.ts"), "x")
        write(os.path.join(root, "master.m3u8"), "#EXTM3U\n720p/index.m3u8\n")
        assert verify_hls(root, ["720p"], False)

def test_missing_segment():
    with tempfile.TemporaryDirectory() as root:
        write(os.path.join(root, "720p", "index.m3u8"), "#EXTM3U\nseg_00000.ts\n")
        write(os.path.join(root, "master.m3u8"), "#EXTM3U\n720p/index.m3u8\n")
        try:
            verify_hls(root, ["720p"], False)
        except RuntimeError as e:
            assert "missing segment" in str(e)
            return
        raise SystemExit("expected missing segment")

def test_paid_needs_key():
    with tempfile.TemporaryDirectory() as root:
        write(os.path.join(root, "360p", "index.m3u8"), "#EXTM3U\nseg_00000.ts\n")
        write(os.path.join(root, "360p", "seg_00000.ts"), "x")
        write(os.path.join(root, "master.m3u8"), "#EXTM3U\n360p/index.m3u8\n")
        try:
            verify_hls(root, ["360p"], True)
        except RuntimeError as e:
            assert "AES" in str(e)
            return
        raise SystemExit("expected missing key")

if __name__ == "__main__":
    test_ok()
    test_missing_segment()
    test_paid_needs_key()
    print("hls verify ok")
