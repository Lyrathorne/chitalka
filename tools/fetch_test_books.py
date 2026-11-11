"""Download five public-domain Gutenberg EPUBs for local QA; never bundle books in APK/Git."""
from pathlib import Path
import hashlib
import json
import socket
import urllib.request
import zipfile
import os
import subprocess

ROOT = Path("outputs/corpus")
BOOKS = [(11, "Alice's Adventures in Wonderland"), (1342, "Pride and Prejudice"),
         (84, "Frankenstein"), (46, "A Christmas Carol"), (43, "Dr Jekyll and Mr Hyde")]
if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    original = socket.getaddrinfo
    socket.getaddrinfo = lambda host, port, *args, **kwargs: original(host, port, socket.AF_INET, socket.SOCK_STREAM)
    manifest = []
    for number, title in BOOKS:
        path = ROOT / f"{number}.epub"
        if not path.exists() or not zipfile.is_zipfile(path):
            for attempt in range(3):
                try:
                    url = f"https://www.gutenberg.org/ebooks/{number}.epub3.images"
                    if os.name == "nt":
                        # Windows' system certificate store; do not disable TLS verification.
                        subprocess.run(["curl.exe", "--silent", "--show-error", "--location", "--fail", "--connect-timeout", "20", "--max-time", "60", "--output", str(path), url], check=True)
                    else:
                        urllib.request.urlretrieve(url, path)
                    assert zipfile.is_zipfile(path)
                    break
                except OSError:
                    if attempt == 2:
                        raise
        manifest.append({"id": number, "title": title, "url": f"https://www.gutenberg.org/ebooks/{number}",
                         "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size})
        print(f"Ready: {number}, {path.stat().st_size} bytes", flush=True)
    (ROOT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
