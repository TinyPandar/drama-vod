"""转码产物完整性检查。READY 之前必须通过，避免客户端看到半成品 HLS。"""
import os


def verify_hls(root, renditions, encrypted):
    master_path = os.path.join(root, "master.m3u8")
    if not os.path.isfile(master_path):
        raise RuntimeError("master.m3u8 missing")
    master = open(master_path, encoding="utf-8").read()
    variants = [line.strip() for line in master.splitlines() if line.strip() and not line.startswith("#")]
    if len(variants) != len(renditions):
        raise RuntimeError(f"master variants {variants} != renditions {renditions}")
    for name in renditions:
        rel = f"{name}/index.m3u8"
        if rel not in variants and f"{name}/index.m3u8" not in variants:
            raise RuntimeError(f"master missing {rel}")
        playlist = os.path.join(root, name, "index.m3u8")
        if not os.path.isfile(playlist):
            raise RuntimeError(f"{rel} missing")
        text = open(playlist, encoding="utf-8").read()
        if encrypted and "EXT-X-KEY" not in text:
            raise RuntimeError(f"{name} missing AES key tag")
        if not encrypted and "EXT-X-KEY" in text:
            raise RuntimeError(f"{name} unexpectedly encrypted")
        segs = [line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")]
        if not segs:
            raise RuntimeError(f"{name} has no segments")
        for seg in segs:
            path = os.path.join(root, name, seg.split("?")[0].split("/")[-1])
            if not os.path.isfile(path):
                raise RuntimeError(f"missing segment {name}/{seg}")
    return True
