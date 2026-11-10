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


def local_manifest(root):
    """本地产物清单 {相对路径: 字节数}，相对路径用 / 分隔，与对象键后缀一致。"""
    out = {}
    for d, _, files in os.walk(root):
        for f in files:
            path = os.path.join(d, f)
            out[os.path.relpath(path, root).replace(os.sep, "/")] = os.path.getsize(path)
    return out


def verify_remote(local, remote, prefix):
    """上传完、提交前核对远端：remote 是 {对象键: 大小}（list_objects_v2 的结果）。
    名字集合和大小都必须与本地一致；任务专属前缀里不应有多余对象。不一致抛 RuntimeError。"""
    expected = {prefix + rel: size for rel, size in local.items()}
    missing = sorted(k for k in expected if k not in remote)
    if missing:
        raise RuntimeError(f"remote missing {len(missing)} objects, e.g. {missing[:3]}")
    wrong = sorted(k for k, size in expected.items() if remote[k] != size)
    if wrong:
        raise RuntimeError(f"remote size mismatch for {len(wrong)} objects, e.g. {wrong[:3]}")
    extra = sorted(k for k in remote if k not in expected)
    if extra:
        raise RuntimeError(f"remote has {len(extra)} unexpected objects, e.g. {extra[:3]}")
    return True
