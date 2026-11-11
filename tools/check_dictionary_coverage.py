"""Exact lookup coverage on local QA EPUBs, not a translation-quality score."""
import collections
import gzip
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import sqlite3
import zipfile


class BodyText(HTMLParser):
    def __init__(self):
        super().__init__(); self.body = False; self.skip = 0; self.text = []
    def handle_starttag(self, tag, attrs):
        if tag == "body": self.body = True
        if tag in ("script", "style"): self.skip += 1
    def handle_endtag(self, tag):
        if tag == "body": self.body = False
        if tag in ("script", "style"): self.skip = max(0, self.skip - 1)
    def handle_data(self, data):
        if self.body and not self.skip: self.text.append(data)


if __name__ == "__main__":
    temporary = Path("outputs/coverage-dictionary.sqlite")
    temporary.write_bytes(gzip.decompress(Path("app/src/main/assets/dictionary/eng-rus.sqlite.gz.bin").read_bytes()))
    db = sqlite3.connect(temporary)
    keys = {row[0] for row in db.execute("SELECT DISTINCT normalized FROM entry")}
    report = []
    for path in sorted(Path("outputs/corpus").glob("*.epub")):
        words = collections.Counter()
        with zipfile.ZipFile(path) as epub:
            for name in epub.namelist():
                if name.lower().endswith((".xhtml", ".html", ".htm")):
                    parser = BodyText(); parser.feed(epub.read(name).decode("utf8", "replace"))
                    words.update(word.lower().replace("’", "'") for word in re.findall(r"[A-Za-z]+(?:['’\-][A-Za-z]+)*", " ".join(parser.text)))
        covered = sum(count for word, count in words.items() if word in keys)
        report.append({"file": path.name, "tokens": sum(words.values()), "types": len(words),
                       "exact_type_coverage_pct": round(len(words.keys() & keys) / len(words) * 100, 2),
                       "exact_token_coverage_pct": round(covered / sum(words.values()) * 100, 2)})
    db.close()
    Path("outputs/corpus/dictionary-coverage.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf8")
    print(json.dumps(report))
