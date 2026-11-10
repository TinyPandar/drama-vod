"""决定一条转码消息落在哪张表、产物写到哪个前缀。未知 origin 一律当旧链路，避免新字段把旧消息带偏。"""
import re

# Phase 4：Core 每次 claim 写到自己的前缀 assets/v1/{asset_id}/{job_id}-{claim_version}/。
# gen 只含数字和一个 -，与 vod-api HlsSigner.validGeneration、Nginx location 正则一致。
GEN_RE = re.compile(r"[0-9]+-[0-9]+")
GEN_PREFIX_RE = re.compile(r"assets/v1/([0-9]+)/([0-9]+-[0-9]+)/")

def origin_of(msg):
    if isinstance(msg, dict) and msg.get("origin") == "core":
        return "core"
    return "legacy"


def job_table(origin):
    if origin == "core":
        return "vod_transcode_job"
    if origin == "legacy":
        return "transcode_job"
    raise ValueError(origin)


def generation(job_id, claim_version):
    return f"{int(job_id)}-{int(claim_version)}"


def hls_prefix(origin, asset_id, job_id=None, claim_version=None):
    """旧 Demo 固定 assets/{id}/（冻结）。Core 必须给 job_id + claim_version，得到任务专属前缀；
    Phase 4 之前写下的固定前缀 assets/v1/{id}/ 只在库里的 hls_prefix 里出现，不再新写。"""
    asset_id = int(asset_id)
    if origin == "core":
        if job_id is None or claim_version is None:
            raise ValueError("core prefix needs job_id and claim_version")
        return f"assets/v1/{asset_id}/{generation(job_id, claim_version)}/"
    return f"assets/{asset_id}/"


def is_generation_prefix(prefix, asset_id=None):
    """prefix 是否是某个资产的任务专属前缀（清理只认这种，固定前缀和旧 Demo 前缀永远不删）。"""
    m = GEN_PREFIX_RE.fullmatch(prefix or "")
    return bool(m) and (asset_id is None or m.group(1) == str(int(asset_id)))


def updates_episode(origin):
    return origin != "core"
