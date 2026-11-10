"""清理 Core 的垃圾输出前缀（vod_output_prefix.status='GARBAGE'）。

一轮（gc_pass）：
  1. 把「过时的 WRITING」改成 GARBAGE：任务已被重新领取（claim_version 变了）、任务已不在 RUNNING、或任务行不存在。
     同版本且仍 RUNNING 的 WRITING 是在跑的 Worker，绝不碰（租约过期也等它被重新领取后再算）。
  2. 取 GARBAGE 且 updated_at 早于宽限期的前缀，一次最多 limit 个；
  3. 每个前缀删之前再确认：仍是 GARBAGE、格式是该资产的任务专属前缀、且不等于资产当前 hls_prefix；
  4. 分页 list + 批量 delete_objects，删完置 DELETED。
Phase 4 之前的固定前缀 assets/v1/{id}/ 和旧 Demo 的 assets/{id}/ 没有行，也过不了第 3 步的格式检查，永远不删。

宽限期要大于租约和播放 URL / token 的最长有效期：被替换的前缀上可能还有正在播放的人和 CDN 缓存。
多个 Worker 同时清理是安全的：删除幂等，DELETED 带条件写。
"""
from job_route import is_generation_prefix


def promote_stale_writing(c):
    return c.execute(
        "UPDATE vod_output_prefix p LEFT JOIN vod_transcode_job j ON j.id=p.job_id "
        "SET p.status='GARBAGE', p.updated_at=CURRENT_TIMESTAMP "
        "WHERE p.status='WRITING' AND (j.id IS NULL OR j.claim_version<>p.claim_version OR j.status<>'RUNNING')")


def due_garbage(c, grace_seconds, limit):
    c.execute("SELECT id, asset_id, prefix FROM vod_output_prefix "
              "WHERE status='GARBAGE' AND updated_at < NOW() - INTERVAL %s SECOND ORDER BY id LIMIT %s",
              (int(grace_seconds), int(limit)))
    return list(c.fetchall())


def still_deletable(c, row):
    """删前复核：仍是 GARBAGE，前缀属于这个资产的任务专属目录，且不是资产当前 hls_prefix。"""
    if not is_generation_prefix(row["prefix"], row["asset_id"]):
        return False
    c.execute("SELECT p.status, a.hls_prefix FROM vod_output_prefix p "
              "LEFT JOIN vod_media_asset a ON a.id=p.asset_id WHERE p.id=%s", (row["id"],))
    cur = c.fetchone()
    return bool(cur) and cur["status"] == "GARBAGE" and cur["hls_prefix"] != row["prefix"]


def delete_prefix(s3c, bucket, prefix, asset_id, batch):
    """分页列出前缀下的对象并批量删除，返回删除个数。部分失败抛异常，行保持 GARBAGE 下轮重试。"""
    if not is_generation_prefix(prefix, asset_id):
        raise ValueError(f"refusing to delete non-generation prefix {prefix!r}")
    batch = max(1, min(1000, int(batch)))
    deleted = 0
    while True:
        # 每次都从头列：上一页刚删掉，继续用 continuation token 会跳过对象
        page = s3c.list_objects_v2(Bucket=bucket, Prefix=prefix, MaxKeys=batch)
        keys = [{"Key": o["Key"]} for o in page.get("Contents", []) if o["Key"].startswith(prefix)]
        if not keys:
            return deleted
        resp = s3c.delete_objects(Bucket=bucket, Delete={"Objects": keys, "Quiet": True})
        if resp.get("Errors"):
            raise RuntimeError(f"delete_objects failed for {len(resp['Errors'])} keys under {prefix}")
        deleted += len(keys)


def mark_deleted(c, row_id):
    c.execute("UPDATE vod_output_prefix SET status='DELETED', updated_at=CURRENT_TIMESTAMP "
              "WHERE id=%s AND status='GARBAGE'", (row_id,))


def gc_pass(c, s3c, bucket, grace_seconds, limit, batch, log=lambda *a, **k: None):
    """返回 (删掉的前缀数, 删掉的对象数, 跳过的前缀数)。c 为自动提交的游标。"""
    promote_stale_writing(c)
    prefixes = objects = skipped = 0
    for row in due_garbage(c, grace_seconds, limit):
        if not still_deletable(c, row):
            skipped += 1
            log("output_gc_skip", prefix=row["prefix"], asset_id=row["asset_id"])
            continue
        n = delete_prefix(s3c, bucket, row["prefix"], row["asset_id"], batch)
        mark_deleted(c, row["id"])
        prefixes += 1
        objects += n
        log("output_gc_deleted", prefix=row["prefix"], asset_id=row["asset_id"], objects=n)
    return prefixes, objects, skipped
