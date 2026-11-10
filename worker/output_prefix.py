"""vod_output_prefix（V11）的状态维护：WRITING → ACTIVE / GARBAGE → DELETED。只用于 Core 的任务专属前缀。

- 上传前插 WRITING（自动提交，INSERT IGNORE 幂等）；
- 成功事务里自己置 ACTIVE，同资产其他 WRITING/ACTIVE 置 GARBAGE（fencing.commit_success 调用）；
- 失败、取消、围栏丢失：自己的 WRITING 置 GARBAGE（取消在 vod-api 的取消事务里做）。
条件里都带 status，ACTIVE 不会被降级；DELETED 只由清理线程（output_gc.py）写。
"""


def insert_writing(c, asset_id, job_id, version, prefix):
    c.execute("INSERT IGNORE INTO vod_output_prefix(asset_id, job_id, claim_version, prefix, status) "
              "VALUES (%s,%s,%s,%s,'WRITING')", (asset_id, job_id, version, prefix))


def activate(c, asset_id, prefix):
    """成功事务内调用（任务行已被本事务锁住，所以不会和别的成功事务交错）。"""
    c.execute("UPDATE vod_output_prefix SET status='ACTIVE', updated_at=CURRENT_TIMESTAMP "
              "WHERE prefix=%s AND status IN ('WRITING','GARBAGE')", (prefix,))
    c.execute("UPDATE vod_output_prefix SET status='GARBAGE', updated_at=CURRENT_TIMESTAMP "
              "WHERE asset_id=%s AND prefix<>%s AND status IN ('WRITING','ACTIVE')", (asset_id, prefix))


def discard(c, prefix):
    """自己没提交成功的前缀：WRITING → GARBAGE。ACTIVE 不动。"""
    c.execute("UPDATE vod_output_prefix SET status='GARBAGE', updated_at=CURRENT_TIMESTAMP "
              "WHERE prefix=%s AND status='WRITING'", (prefix,))
