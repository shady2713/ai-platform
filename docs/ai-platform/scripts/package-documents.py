"""打包已验证文档；通过参数指定输出，不依赖原电脑路径。"""
from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description="打包AI方案文档。")
parser.add_argument("--output", type=Path, help="ZIP目标路径；默认放项目.local-state/packages。")
arguments = parser.parse_args()
project_root = root.parent.parent if root.parent.name == "docs" else root.parent
archive_path = (arguments.output or project_root / ".local-state/packages/ai-platform-docs.zip").resolve()
if root == archive_path or root in archive_path.parents:
    parser.error("输出ZIP不能放在正在打包的文档目录内。")
archive_path.parent.mkdir(parents=True, exist_ok=True)
result = json.loads((root / "verification/results.json").read_text(encoding="utf-8"))
if not result["passed"]:
    raise SystemExit("文档验证未通过，拒绝打包")

manifest_path = root / "verification/delivery-manifest.json"
files = sorted(p for p in root.rglob("*") if p.is_file() and p != manifest_path and "__pycache__" not in p.parts)
manifest = {"scope": "DOCUMENT_DELIVERY_ONLY", "files": [{"path": p.relative_to(root).as_posix(), "bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]}
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in [*files, manifest_path]:
        archive.write(path, arcname=root.name + "/" + path.relative_to(root).as_posix())
with zipfile.ZipFile(archive_path) as archive:
    if archive.testzip() is not None:
        raise SystemExit("压缩包CRC验证失败")
    for entry in manifest["files"]:
        data = archive.read(root.name + "/" + entry["path"])
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise SystemExit("压缩包内容哈希不匹配")
print(json.dumps({"archive": str(archive_path), "fileCount": len(files) + 1, "bytes": archive_path.stat().st_size, "sha256": hashlib.sha256(archive_path.read_bytes()).hexdigest(), "crcAndContentHashes": "PASS"}, ensure_ascii=False))
