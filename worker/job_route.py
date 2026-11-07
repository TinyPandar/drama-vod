"""决定一条转码消息落在哪张表、产物写到哪个前缀。未知 origin 一律当旧链路，避免新字段把旧消息带偏。"""

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


def hls_prefix(origin, asset_id):
    asset_id = int(asset_id)
    if origin == "core":
        return f"assets/v1/{asset_id}/"
    return f"assets/{asset_id}/"


def updates_episode(origin):
    return origin != "core"
