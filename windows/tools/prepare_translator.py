"""Assemble the pinned, private-to-the-app Python runtime; no global Python is used by releases."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
source = ROOT / "outputs/windows"
target = ROOT / "outputs/windows/translator-runtime"
target.mkdir(parents=True, exist_ok=True)
archive = source / "python-embed.zip"
assert hashlib.sha256(archive.read_bytes()).hexdigest() == "1ec066fb61ba5e8c73e29e048cd07c26850f74585e3a116005135b31b8004890"
with zipfile.ZipFile(archive) as z:
    z.extractall(target)
manifest = []
for wheel in sorted((source / "wheels").glob("*.whl")):
    with zipfile.ZipFile(wheel) as z:
        z.extractall(target / "Lib/site-packages")
    manifest.append({"file": wheel.name, "sha256": hashlib.sha256(wheel.read_bytes()).hexdigest()})
(target / "python313._pth").write_text("python313.zip\n.\nLib/site-packages\nimport site\n", encoding="utf8")
(source / "translator-runtime-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf8")
print(target)
