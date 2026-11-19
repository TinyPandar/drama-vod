"""路线 C：短剧 Demo 的观众账号。只放不连库的纯函数：口令哈希、会话 token、用户名口令校验。

口令：PBKDF2-HMAC-SHA256，每个用户随机 16 字节盐，存成 pbkdf2_sha256$迭代次数$盐$哈希（base64url）。
会话 token：dsess1.<base64url(JSON{u, exp})>.<HMAC-SHA256 hex>，密钥 DEMO_SESSION_SECRET。
它只是 Demo 业务的登录态，和 Core 的播放 token（vpt1.）、旧 Demo 的播放 token 都不通用。
"""
import base64, hashlib, hmac, json, os, re, time

PREFIX = "dsess1."
ITERATIONS = 200_000
USERNAME_RE = re.compile(r"[A-Za-z0-9_.-]{3,32}")


def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def valid_username(name) -> bool:
    return isinstance(name, str) and USERNAME_RE.fullmatch(name) is not None


def valid_password(pw) -> bool:
    return isinstance(pw, str) and 8 <= len(pw) <= 128


def hash_password(password: str, iterations: int = ITERATIONS, salt: bytes | None = None) -> str:
    salt = salt or os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
    return f"pbkdf2_sha256${iterations}${_b64(salt)}${_b64(digest)}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, iterations, salt, digest = stored.split("$")
        if algo != "pbkdf2_sha256":
            return False
        got = hashlib.pbkdf2_hmac("sha256", password.encode(), _unb64(salt), int(iterations))
        return hmac.compare_digest(got, _unb64(digest))
    except (ValueError, TypeError):
        return False


def issue_session(user_id: int, secret: bytes, ttl_seconds: int, now: float | None = None) -> tuple[str, int]:
    exp = int(now if now is not None else time.time()) + int(ttl_seconds)
    payload = _b64(json.dumps({"u": int(user_id), "exp": exp}, separators=(",", ":")).encode())
    sig = hmac.new(secret, payload.encode(), hashlib.sha256).hexdigest()
    return f"{PREFIX}{payload}.{sig}", exp


def verify_session(token, secret: bytes, now: float | None = None) -> int | None:
    """合法且没过期返回 user_id，否则 None。"""
    if not isinstance(token, str) or not token.startswith(PREFIX):
        return None
    try:
        payload, sig = token[len(PREFIX):].split(".")
        want = hmac.new(secret, payload.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(sig, want):
            return None
        data = json.loads(_unb64(payload))
        if int(data["exp"]) < (now if now is not None else time.time()):
            return None
        return int(data["u"])
    except (ValueError, KeyError, TypeError):
        return None


def bearer(authorization) -> str:
    if not isinstance(authorization, str):
        return ""
    value = authorization.strip()
    return value[7:].strip() if value[:7].lower() == "bearer " else ""
