"""Reproducible int8 model ZIP; source weights converted with OpusMTConverter."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

root = Path(__file__).resolve().parents[2]
folder = root / "outputs/windows/model-package"
source = root / "outputs/windows/opus-original"
for name in ["source.spm", "target.spm", "README.md", "LICENSE"]:
    shutil.copyfile(source / name, folder / name)
target = root / "outputs/windows/chitalka-model-en-ru-v1.zip"
with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for f in sorted(folder.rglob("*")):
        if f.is_file():
            info = zipfile.ZipInfo(f.relative_to(folder).as_posix(), date_time=(2026, 11, 11, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, f.read_bytes())
manifest = {
    "version": "opus-en-ru-int8-v1",
    "url": "https://github.com/Lyrathorne/chitalka/releases/download/windows-model-v1/chitalka-model-en-ru-v1.zip",
    "bytes": target.stat().st_size,
    "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
    "source": "https://object.pouta.csc.fi/OPUS-MT-models/en-ru/opus-2020-02-11.zip",
    "source_sha256": hashlib.sha256((root / "outputs/windows/opus-en-ru.zip").read_bytes()).hexdigest(),
    "license": "CC BY 4.0 (LICENSE in original OPUS archive)",
    "changes": "CTranslate2 4.6.3 OpusMTConverter, int8; source and target SentencePiece unchanged"
}
(root / "windows/Translator/model-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf8")
print(json.dumps(manifest, indent=2))
