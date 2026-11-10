"""短剧 Demo 调用视频云。解锁不在这里。

返回 (HTTP 状态码, JSON)。视频云连不上、超时、返回的不是 JSON 时按 502 返回（{"detail": ...}），
调用方不会因为 Core 宕机抛出未处理异常变成 500。
"""
import json, os, socket, urllib.error, urllib.request

BASE = os.environ.get("VOD_API_BASE", "http://vod-api:8001").rstrip("/")
KEY = os.environ.get("VOD_API_KEY", "")
TIMEOUT = float(os.environ.get("VOD_API_TIMEOUT_SECONDS") or 15)
UNAVAILABLE = 502


def call(method, path, body=None, transport=None):
    if transport:
        return transport(method, path, body)
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        BASE + path, data=data, method=method,
        headers={"X-Api-Key": KEY, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            raw = resp.read().decode() or "{}"
            try:
                return resp.status, json.loads(raw)
            except ValueError:
                return UNAVAILABLE, {"detail": "vod-api returned non-json"}
    except urllib.error.HTTPError as e:
        raw = e.read().decode() or "{}"
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"detail": raw[:200]}
        return e.code, payload
    except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError) as e:
        # URLError 包住连接拒绝、DNS 失败；读超时是 socket.timeout / TimeoutError
        return UNAVAILABLE, {"detail": f"vod-api unavailable: {type(getattr(e, 'reason', e)).__name__}"}


def open_upload(transport=None):
    return call("POST", "/api/v1/uploads", {}, transport)


def complete_upload(session_id, transport=None):
    return call("POST", f"/api/v1/uploads/{session_id}/complete", None, transport)


def playback(asset_id, transport=None):
    return call("GET", f"/api/v1/assets/{asset_id}/playback", None, transport)


def playback_token(asset_id, ttl_seconds=None, viewer_id=None, transport=None):
    """POST /api/v1/assets/{id}/playback-token：返回 token、master_url、expires_at、encrypted。"""
    body = {}
    if ttl_seconds is not None:
        body["ttl_seconds"] = int(ttl_seconds)
    if viewer_id is not None:
        body["viewer_id"] = str(viewer_id)
    return call("POST", f"/api/v1/assets/{asset_id}/playback-token", body, transport)
