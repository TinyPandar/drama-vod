#!/usr/bin/env python3
"""Phase 0 契约检查。不启动 Docker。只核对仓库里的 schema 和发布代码没有分叉。"""
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "docs" / "contracts" / "transcode-message.v1.schema.json"
OPENAPI = ROOT / "docs" / "contracts" / "openapi.yaml"
errors = []


def fail(msg):
    errors.append(msg)
    print("FAIL", msg)


def check(name, ok):
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        errors.append(name)


def matches(value, schema):
    if "const" in schema and value != schema["const"]:
        return False
    types = schema.get("type")
    if isinstance(types, str):
        types = [types]
    if types:
        ok = False
        for t in types:
            if t == "integer" and type(value) is int:
                ok = True
            elif t == "string" and isinstance(value, str):
                ok = True
            elif t == "null" and value is None:
                ok = True
            elif t == "object" and isinstance(value, dict):
                ok = True
        if not ok:
            return False
    if schema.get("type") == "integer" and "minimum" in schema and value < schema["minimum"]:
        return False
    if schema.get("type") == "object":
        if not isinstance(value, dict):
            return False
        for key in schema.get("required", []):
            if key not in value:
                return False
        props = schema.get("properties", {})
        for key, sub in props.items():
            if key in value and not matches(value[key], sub):
                return False
    return True


schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
for i, example in enumerate(schema["examples"]):
    check(f"message example {i} matches publisher schema", matches(example, schema))

legacy = {"job_id": 1, "asset_id": 2, "trace_id": None}
check("legacy message without schema_version is not a publisher example", "schema_version" not in legacy)

fn = re.compile(r"def transcode_message\(.*?\n(?:.*\n)*?    return \{.*?\}\n", re.M)
bodies = []
for rel in ("api/common.py", "worker/common.py"):
    text = (ROOT / rel).read_text(encoding="utf-8")
    found = fn.search(text)
    check(f"{rel} defines transcode_message", found is not None)
    if found:
        bodies.append(found.group(0))
check("api/common.py and worker/common.py emit the same message helper", len(bodies) == 2 and bodies[0] == bodies[1])

api = (ROOT / "api" / "main.py").read_text(encoding="utf-8")
worker = (ROOT / "worker" / "worker.py").read_text(encoding="utf-8")
check("api publishes transcode_message", "transcode_message(job[\"id\"], asset_id, x_request_id)" in api)
check("worker sweeper publishes transcode_message", "transcode_message(row[\"id\"], row[\"asset_id\"], row[\"trace_id\"])" in worker)
handler = worker.split("def on_message", 1)[-1].split("def sweeper", 1)[0]
check("worker does not read schema_version", 'msg["schema_version"]' not in handler and 'msg.get("schema_version")' not in handler)

openapi = OPENAPI.read_text(encoding="utf-8")
for path in ("/api/episodes/{episode_id}/upload-url", "/api/assets/{asset_id}/complete",
             "/api/episodes/{episode_id}/play", "/keys/{asset_id}"):
    check(f"openapi freezes {path}", path in openapi)

print(f"\n{len(errors)} failed" if errors else "\ncontract check ok")
sys.exit(1 if errors else 0)
