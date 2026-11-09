"""失败分类与退避。不依赖数据库和对象存储，单测直接调用。

error_code 只写 Core 任务（vod_transcode_job.error_code）；旧 Demo 任务按 Phase 0 契约全部按可重试处理。
"""
import random

# 不可重试：再跑一次结果也一样，直接 FAILED + 进死信队列
INPUT_INVALID = "INPUT_INVALID"        # ffprobe 解析不了源文件
NO_VIDEO_STREAM = "NO_VIDEO_STREAM"    # 源文件没有视频流
SOURCE_MISSING = "SOURCE_MISSING"      # 源对象在对象存储里不存在（404）
# 可重试：依赖或环境暂时出问题
STORAGE_ERROR = "STORAGE_ERROR"        # 对象存储读写失败、连不上
DB_ERROR = "DB_ERROR"
TIMEOUT = "TIMEOUT"                    # ffprobe/ffmpeg 超过配置的超时
TRANSCODE_FAILED = "TRANSCODE_FAILED"  # ffmpeg 非 0 退出（可能是磁盘、内存等环境问题）
OUTPUT_INVALID = "OUTPUT_INVALID"      # 本地 HLS 校验没过
IO_ERROR = "IO_ERROR"
INTERNAL = "INTERNAL"

NON_RETRYABLE = frozenset({INPUT_INVALID, NO_VIDEO_STREAM, SOURCE_MISSING})


class JobError(Exception):
    """流水线里能明确分类的失败。"""

    def __init__(self, code, message, retryable=None):
        super().__init__(message)
        self.code = code
        self.retryable = code not in NON_RETRYABLE if retryable is None else retryable


class FenceLost(Exception):
    """围栏丢了（租约被别人接手、任务被取消）：不写任何状态，直接丢弃。"""


def classify(exc):
    """返回 (error_code, retryable)。认不出的异常按可重试处理，和改动前的行为一致。"""
    if isinstance(exc, JobError):
        return exc.code, exc.retryable
    module = type(exc).__module__ or ""
    if module.startswith(("botocore", "boto3", "s3transfer", "urllib3")):
        return STORAGE_ERROR, True
    if module.startswith("pymysql"):
        return DB_ERROR, True
    if isinstance(exc, OSError):
        return IO_ERROR, True
    return INTERNAL, True


def backoff_ms(attempt, base_ms, max_ms, jitter, rnd=random.random):
    """第 attempt 次失败后的等待：min(max, base * 2^(attempt-1))，再按 jitter 比例随机缩短。

    结果落在 [d * (1 - jitter), d]，d 为不带抖动的上限；jitter 取 0~1。
    """
    attempt = max(1, int(attempt))
    ceiling = min(max_ms, base_ms * 2 ** min(attempt - 1, 30))
    jitter = min(1.0, max(0.0, jitter))
    return max(1, int(ceiling * (1 - jitter * rnd())))


def plan_failure(origin, attempts, max_attempts, exc, base_ms, max_ms, jitter, rnd=random.random):
    """失败后怎么收尾：返回 (final, error_code, delay_ms)。

    Core：不可重试错误首次即 final；旧 Demo：不分类，只看次数（Phase 0 冻结了这条结果）。
    """
    code, retryable = classify(exc)
    if origin != "core":
        code, retryable = None, True
    final = (not retryable) or attempts >= max_attempts
    return final, code, None if final else backoff_ms(attempts, base_ms, max_ms, jitter, rnd)
