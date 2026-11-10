"""启动时检查签名/回源密钥（Phase 4 第 6 项）。和 vod-api 的 vod.security.SecretGuard、nginx/entrypoint.d/05-vod-env.envsh 同一套规则：

VOD_ENV 不是 dev 时，密钥为空、等于 dev 或短于 VOD_MIN_SECRET_LENGTH 就拒绝启动；dev 下只警告。
日志只写变量名，不写值。生成：openssl rand -hex 32，放进 .env，不进仓库。
"""

DEV = "dev"


def is_dev(env):
    return env is None or not str(env).strip() or str(env).strip().lower() == DEV


def weak(value, min_length):
    return value is None or not value.strip() or value.strip().lower() == DEV or len(value) < max(1, int(min_length))


def dev_fallback(value, env):
    """dev 下为空的密钥退回 dev（保证一条命令起栈）；非 dev 原样返回，由 check_secrets 拒绝。"""
    return DEV if (not value and is_dev(env)) else value


def check_secrets(secrets, env, min_length, warn=print):
    """secrets: {变量名: 值}。非 dev 有弱密钥抛 RuntimeError；dev 调 warn。返回弱密钥名列表。"""
    bad = sorted(name for name, value in secrets.items() if weak(value, min_length))
    if bad:
        msg = f"weak or missing secrets {bad} (empty, 'dev' or shorter than {int(min_length)} chars)"
        if not is_dev(env):
            raise RuntimeError(f"{msg}; refusing to start with VOD_ENV={env}")
        warn(f"{msg}; allowed only because VOD_ENV=dev")
    return bad
