"""Validate the bundled package without network or modifying any user database."""
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3

root = Path(__file__).resolve().parents[1] / "app/src/main/assets/dictionary"
manifest = json.loads((root / "manifest.json").read_text(encoding="utf8"))
raw = gzip.decompress((root / "eng-rus.sqlite.gz.bin").read_bytes())
assert manifest["schema"] == 1
assert len(raw) == manifest["bytes"]
assert hashlib.sha256(raw).hexdigest() == manifest["sha256"]
database = sqlite3.connect(":memory:")
database.deserialize(raw)
assert database.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
assert database.execute("PRAGMA user_version").fetchone()[0] == 1
assert database.execute("SELECT COUNT(*) FROM entry").fetchone()[0] == int(manifest["senses"])
assert database.execute("SELECT COUNT(DISTINCT normalized) FROM entry").fetchone()[0] == int(manifest["headwords"])
assert dict(database.execute("SELECT * FROM metadata"))["version"] == manifest["version"]
assert database.execute("SELECT COUNT(*) FROM entry WHERE normalized=? AND translation=?", ("curious", "любопытный")).fetchone()[0] > 0
database.close()
print(f"Dictionary integrity OK: {manifest['headwords']} headwords, {manifest['senses']} entries")
