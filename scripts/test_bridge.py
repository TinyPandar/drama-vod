#!/usr/bin/env python3
"""不需要 Docker。检查核心任务和旧任务不会写到同一个对象前缀。"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "worker"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "api"))
from job_route import origin_of, job_table, hls_prefix, updates_episode
from origin_path import object_key

def raises(fn):
    try:
        fn()
    except ValueError:
        return True
    return False

def check(name, ok):
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        raise SystemExit(1)

check("legacy message stays on old table", origin_of({"job_id": 1}) == "legacy" and job_table("legacy") == "transcode_job")
check("core message uses vod table", origin_of({"origin": "core", "job_id": 1}) == "core" and job_table("core") == "vod_transcode_job")
check("prefixes do not collide", hls_prefix("legacy", 4) == "assets/4/" and hls_prefix("core", 4) == "assets/v1/4/")
check("core job does not rewrite episode", updates_episode("core") is False and updates_episode("legacy") is True)
check("origin path rejects traversal", raises(lambda: object_key("legacy", "4", "../secret")))
check("v1 origin key", object_key("v1", "4", "master.m3u8") == "assets/v1/4/master.m3u8")
print("bridge ok")
