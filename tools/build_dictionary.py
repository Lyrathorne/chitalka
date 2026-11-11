"""Build the independent CC BY-SA 3.0 dictionary asset from pinned FreeDict/WikDict TEI.

Python standard library only. The personal Room database is never opened.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET

URL = "https://download.freedict.org/generated/eng-rus/eng-rus.tei"
SOURCE_SHA256 = "7c48ef3981903361ea90f8ef9a64e58cc7899341d0fb873c2f61198198cba7af"
VERSION = "freedict-wikdict-eng-rus-2025.11.23-v1"
NS = {"t": "http://www.tei-c.org/ns/1.0"}


def normalize(text):
    return unicodedata.normalize("NFC", text).strip().replace("’", "'").replace("‐", "-").replace("‑", "-").lower()


def plain(element):
    text = " ".join("".join(element.itertext()).split())
    text = re.sub(r"\[\[([^\]|]+)(?:\|([^\]]+))?\]\]", lambda m: m[2] or m[1], text)
    return text.replace("\u0301", "")


def build(source, destination):
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Source changed: review its version/license before updating the pinned hash")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = destination.with_suffix(".sqlite.tmp")
    temp.unlink(missing_ok=True)
    db = sqlite3.connect(temp)
    db.executescript("""
        PRAGMA page_size=4096;
        CREATE TABLE entry(id INTEGER PRIMARY KEY, headword TEXT NOT NULL,
          normalized TEXT NOT NULL, pos TEXT NOT NULL, translation TEXT NOT NULL, definition TEXT NOT NULL);
        CREATE INDEX entry_normalized ON entry(normalized);
        CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        PRAGMA user_version=1;
    """)
    records = set()
    for _, element in ET.iterparse(source, events=("end",)):
        if element.tag != "{http://www.tei-c.org/ns/1.0}entry":
            continue
        heads = [plain(e) for e in element.findall("./t:form/t:orth", NS)]
        pos = ", ".join(dict.fromkeys(plain(e) for e in element.findall("./t:gramGrp/t:pos", NS)))
        for sense in element.findall(".//t:sense", NS):
            # Only direct translations: do not merge unrelated nested senses.
            translations = [plain(q) for cit in sense.findall("./t:cit", NS)
                            if cit.get("type") == "trans" and cit.get("{http://www.w3.org/XML/1998/namespace}lang") == "ru"
                            for q in cit.findall("./t:quote", NS)]
            definitions = " / ".join(dict.fromkeys(plain(d) for d in sense.findall(".//t:def", NS)))
            for head in heads:
                for translation in dict.fromkeys(translations):
                    if head and translation:
                        records.add((head, normalize(head), pos, translation, definitions))
        element.clear()
    rows = sorted(records, key=lambda row: tuple(row))
    db.executemany("INSERT INTO entry(headword,normalized,pos,translation,definition) VALUES(?,?,?,?,?)", rows)
    metadata = {"version": VERSION, "source_url": URL, "source_sha256": SOURCE_SHA256,
                "license": "CC BY-SA 3.0", "license_url": "https://creativecommons.org/licenses/by-sa/3.0/",
                "attribution": "English-Русский FreeDict+WikDict dictionary; Karl Bartel; Wiktionary contributors via DBnary",
                "changes": "Converted TEI to indexed SQLite, normalized lookup keys, retained Russian translations and associated English definitions.",
                "headwords": str(len({r[1] for r in rows})), "senses": str(len(rows))}
    db.executemany("INSERT INTO metadata VALUES(?,?)", sorted(metadata.items()))
    db.commit()
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    db.execute("VACUUM")
    db.close()
    raw = temp.read_bytes()
    manifest = {**metadata, "schema": 1, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
    destination.write_bytes(gzip.compress(raw, compresslevel=9, mtime=0))
    destination.with_name("manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    temp.unlink()
    print(json.dumps({"headwords": metadata["headwords"], "senses": len(rows), "bytes": len(raw), "compressed": destination.stat().st_size}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("outputs/eng-rus-2025.11.23.tei"))
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/dictionary/eng-rus.sqlite.gz.bin"))
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if args.download:
        args.source.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(URL, args.source)
    build(args.source, args.output)
