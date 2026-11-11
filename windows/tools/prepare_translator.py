"""Assemble the pinned, private-to-the-app Python runtime; no global Python is used by releases."""
import hashlib
import json
from pathlib import Path
import zipfile
import subprocess

ROOT = Path(__file__).resolve().parents[2]
source = ROOT / "outputs/windows"
target = ROOT / "outputs/windows/translator-runtime"
target.mkdir(parents=True, exist_ok=True)
archive = source / "python-embed.zip"
def fetch(url, path, expected):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        subprocess.run(["curl.exe", "--fail", "--location", "--retry", "3", url, "--output", str(path)], check=True)
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise RuntimeError("Checksum differs: " + path.name)
fetch("https://www.python.org/ftp/python/3.13.11/python-3.13.11-embed-amd64.zip", archive, "1ec066fb61ba5e8c73e29e048cd07c26850f74585e3a116005135b31b8004890")
for item in json.loads((Path(__file__).parent / "runtime-lock.json").read_text()):
    fetch(item["url"], source / "wheels" / item["file"], item["sha256"])
assert hashlib.sha256(archive.read_bytes()).hexdigest() == "1ec066fb61ba5e8c73e29e048cd07c26850f74585e3a116005135b31b8004890"
with zipfile.ZipFile(archive) as z:
    z.extractall(target)
manifest = []
for item in json.loads((Path(__file__).parent / "runtime-lock.json").read_text()):
    wheel = source / "wheels" / item["file"]
    with zipfile.ZipFile(wheel) as z:
        z.extractall(target / "Lib/site-packages")
    manifest.append({"file": wheel.name, "sha256": hashlib.sha256(wheel.read_bytes()).hexdigest()})
(target / "python313._pth").write_text("python313.zip\n.\nLib/site-packages\nimport site\n", encoding="utf8")
(source / "translator-runtime-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf8")
print(target)
