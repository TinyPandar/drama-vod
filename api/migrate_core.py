"""路线 C：把还挂在旧链路资产上的集迁到视频云（Core）。在 api 容器里运行：

  docker compose exec api python migrate_core.py --dry-run   # 只列出要迁的集
  docker compose exec api python migrate_core.py             # 真迁

对每一集（有旧资产且 READY、还没有 core_asset_id、也没有正在等待的 Core 上传）：
  1. 在 Core 开一个上传会话（付费集是加密资产），记进 episode_core_upload（source=migrate）；
  2. 在源桶里把旧原片服务端复制到 Core 的源对象键（不经过本机，不重新上传）；
  3. 调 Core complete 建转码任务。
转码完成后由 api 的对账线程把这一集切到新的 Core 资产；切换前这一集仍按旧链路播放，不中断。
可以重复运行：已迁过或正在迁的集会跳过。旧原片和旧 HLS 产物不删除。
"""
import argparse, sys
from urllib.parse import unquote, urlparse

import main
from common import SOURCE_BUCKET


def candidates(conn):
    with conn.cursor() as c:
        c.execute("SELECT e.id AS episode_id, e.is_paid, a.id AS legacy_asset_id, a.source_bucket, a.source_key "
                  "FROM episode e JOIN video_asset a ON a.id = e.asset_id "
                  "WHERE e.core_asset_id IS NULL AND a.status = 'READY' "
                  "AND NOT EXISTS (SELECT 1 FROM episode_core_upload u WHERE u.episode_id = e.id "
                  "AND u.status IN ('PENDING', 'SWITCHED')) ORDER BY e.id")
        return c.fetchall()


def object_key_of(put_url, bucket):
    """预签名地址是 path-style：/{bucket}/{key}?X-Amz-…，从里面取出 Core 分配的源对象键。"""
    path = unquote(urlparse(put_url).path).lstrip("/")
    if not path.startswith(bucket + "/"):
        raise ValueError(f"unexpected upload url path {path!r}")
    return path[len(bucket) + 1:]


def migrate_one(row):
    payload = main.open_core_upload(row["episode_id"], source="migrate")
    key = object_key_of(payload["put_url"], SOURCE_BUCKET)
    main.s3_internal.copy_object(Bucket=SOURCE_BUCKET, Key=key,
                                 CopySource={"Bucket": row["source_bucket"], "Key": row["source_key"]},
                                 ContentType="video/mp4", MetadataDirective="REPLACE")
    done = main.complete_core_upload(main.core_upload(payload["asset_id"]), trace_id=f"migrate-ep-{row['episode_id']}")
    return payload["asset_id"], done.get("job_id")


def run(dry_run=False, out=print):
    main.ensure_demo_schema()
    conn = main.db()
    rows = candidates(conn)
    conn.close()
    out(f"{len(rows)} episode(s) to migrate")
    ok = failed = 0
    for row in rows:
        if dry_run:
            out(f"  episode {row['episode_id']}: legacy asset {row['legacy_asset_id']} ({row['source_key']})")
            continue
        try:
            core_asset, job = migrate_one(row)
            out(f"  episode {row['episode_id']}: legacy asset {row['legacy_asset_id']} -> core asset {core_asset}, job {job}")
            ok += 1
        except Exception as e:  # 一集失败不影响其他集；再运行一次会重试（失败的 PENDING 上传会被对账线程标掉）
            out(f"  episode {row['episode_id']}: FAILED {getattr(e, 'detail', e)}")
            failed += 1
    if not dry_run:
        out(f"migrated {ok}, failed {failed}; episodes switch to Core when their transcode is READY")
    return ok, failed


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    _, failures = run(args.dry_run)
    sys.exit(1 if failures else 0)
