"""短剧 Demo 调用视频云。解锁不在这里。"""
import json, os, urllib.error, urllib.request

BASE = os.environ.get("VOD_API_BASE", "http://vod-api:8001").rstrip("/")
KEY = os.environ.get("VOD_API_KEY", "")


def call(method, path, body=None, transport=None):
    if transport:
        return transport(method, path, body)
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        BASE + path, data=data, method=method,
        headers={"X-Api-Key": KEY, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode() or "{}"
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode() or "{}"
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"detail": raw[:200]}
        return e.code, payload


def open_upload(transport=None):
    return call("POST", "/api/v1/uploads", {}, transport)


def complete_upload(session_id, transport=None):
    return call("POST", f"/api/v1/uploads/{session_id}/complete", None, transport)


def playback(asset_id, transport=None):
    return call("GET", f"/api/v1/assets/{asset_id}/playback", None, transport)
