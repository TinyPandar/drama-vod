"""Phase 5：Core 任务按 job_id 读转码模板（vod_transcode_template，V12）。消息 v1 不带梯队，模板改动只影响之后领取的任务。

template_id 有值用它；为空（Phase 5 之前建的任务、complete 建的默认任务）按 profile 找系统模板（tenant_id=0）；
都没有就用内置梯队——和 Phase 0 冻结、V12 种子的 ladder_v1 一样。旧 Demo 不走这里，仍用 worker.LADDER。
"""
import json

from retry_policy import TEMPLATE_INVALID, JobError

# 与 worker.LADDER、V12 种子的 ladder_v1 一致：(档名, 短边像素, 视频码率 kbps)
DEFAULT_LADDER = [("720p", 720, 2500), ("540p", 540, 1200), ("360p", 360, 600)]
DEFAULT_AUDIO_KBPS = 96

SQL = ("SELECT COALESCE(t.ladder_json, s.ladder_json) AS ladder_json, COALESCE(t.audio_kbps, s.audio_kbps) AS audio_kbps "
       "FROM vod_transcode_job j "
       "LEFT JOIN vod_transcode_template t ON t.id = j.template_id "
       "LEFT JOIN vod_transcode_template s ON s.tenant_id = 0 AND s.name = j.profile "
       "WHERE j.id=%s")


def parse_ladder(raw):
    """[[name, short_edge, kbps], ...] → [(name, short_edge, kbps)]。和 vod-api TemplateService 的校验同口径。"""
    try:
        rungs = json.loads(raw) if isinstance(raw, str) else raw
        out = []
        for name, edge, kbps in rungs:
            if not (isinstance(name, str) and name.endswith("p") and name[:-1].isdigit()):
                raise ValueError(f"bad rung name {name!r}")
            edge, kbps = int(edge), int(kbps)
            if edge <= 0 or edge % 2 or kbps <= 0:
                raise ValueError("bad rung numbers")
            out.append((name, edge, kbps))
    except (TypeError, ValueError) as e:
        raise JobError(TEMPLATE_INVALID, f"template ladder invalid: {e}") from e
    if not out:
        raise JobError(TEMPLATE_INVALID, "template ladder empty")
    return sorted(out, key=lambda r: -r[1])


def load(c, job_id):
    """返回 (ladder, audio_kbps)。查不到行或模板（fetchone 为 None）时用内置梯队。"""
    c.execute(SQL, (job_id,))
    row = c.fetchone()
    if not row or not row.get("ladder_json"):
        return list(DEFAULT_LADDER), DEFAULT_AUDIO_KBPS
    audio = row.get("audio_kbps") or DEFAULT_AUDIO_KBPS
    return parse_ladder(row["ladder_json"]), int(audio)
