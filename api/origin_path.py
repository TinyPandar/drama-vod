"""把边缘回源请求收成对象键。拒绝路径穿越，避免签名过了还能读到别的对象。"""

def object_key(scope, asset_id, file_path):
    if scope not in ("legacy", "v1"):
        raise ValueError("scope")
    if not str(asset_id).isdigit():
        raise ValueError("asset")
    if not file_path or file_path.startswith("/") or "\\" in file_path or ".." in file_path:
        raise ValueError("path")
    parts = file_path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError("path")
    prefix = f"assets/{asset_id}/" if scope == "legacy" else f"assets/v1/{asset_id}/"
    return prefix + file_path
