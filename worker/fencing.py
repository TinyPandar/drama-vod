"""资产状态更新的 claim_version 围栏。

任务表的状态更新已经带 claim_version；资产状态也必须只在仍持有当前 claim 时改，
否则旧 worker（租约过期后）会把新 worker 写好的 READY 盖成 TRANSCODING/FAILED。
"""
from job_route import job_table

ASSET_TABLE = {"core": "vod_media_asset", "legacy": "video_asset"}


def update_asset_status(c, origin, asset_id, job_id, version, status):
    """资产状态 + 任务围栏一起更新。返回影响行数；1 表示围栏仍属于自己。"""
    asset = ASSET_TABLE[origin]
    n = c.execute(
        f"UPDATE {asset} a JOIN {job_table(origin)} j ON j.asset_id=a.id "
        "SET a.status=%s WHERE a.id=%s AND j.id=%s AND j.claim_version=%s",
        (status, asset_id, job_id, version))
    if n == 0:
        # MySQL 只统计实际变化的行：状态本来就是目标值时也是 0。再确认一次围栏，
        # 避免把"重试时资产已经是 TRANSCODING"误判成围栏丢失。
        return 1 if _fence_held(c, origin, asset_id, job_id, version) else 0
    return n


def _fence_held(c, origin, asset_id, job_id, version):
    c.execute(
        f"SELECT 1 FROM {ASSET_TABLE[origin]} a JOIN {job_table(origin)} j ON j.asset_id=a.id "
        "WHERE a.id=%s AND j.id=%s AND j.claim_version=%s LIMIT 1",
        (asset_id, job_id, version))
    return c.fetchone() is not None


def apply_failure(c, origin, asset_id, job_id, version, job_changed, final):
    """失败收尾：任务 UPDATE 没抢到（围栏已丢）就什么都别写，返回 False。

    返回 True 表示围栏仍属于自己，调用方可以照常投递 retry/DLQ。
    """
    if job_changed != 1:
        return False
    if final:
        update_asset_status(c, origin, asset_id, job_id, version, "FAILED")
    return True
