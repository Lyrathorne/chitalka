"""Create a small original EPUB for repeatable reader integration tests."""
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED, ZIP_DEFLATED

target = Path(__file__).resolve().parents[1] / "app/src/main/assets/sample.epub"
target.parent.mkdir(parents=True, exist_ok=True)
paragraphs = [
    "Mira was curious about the quiet garden behind the old library. Every morning she walked past its green gate, carrying a book and a small notebook.",
    "One day the gate was open. She stepped inside and found a wooden bench beneath an apple tree. The leaves moved softly in the wind, and the city sounded far away.",
    "She opened her book, but a handwritten note slipped from its pages. It said: A good story gives you a place to return to. She smiled and saved the sentence in her notebook.",
    "There was no hurry. She could read a paragraph, look at the sky, and begin again. Sometimes a single word was enough to change the colour of the whole afternoon.",
]
with ZipFile(target, "w") as z:
    z.writestr("mimetype", "application/epub+zip", compress_type=ZIP_STORED)
    z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>', compress_type=ZIP_DEFLATED)
    z.writestr("EPUB/book.opf", '''<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="uid" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:chitalka:quiet-garden:1</dc:identifier><dc:title>The Quiet Garden</dc:title><dc:creator>Chitalka · Original demo</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-11-10T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>''', compress_type=ZIP_DEFLATED)
    z.writestr("EPUB/nav.xhtml", '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter1.xhtml">The Open Gate</a></li><li><a href="chapter2.xhtml">A Place to Return</a></li></ol></nav></body></html>', compress_type=ZIP_DEFLATED)
    for i, title in enumerate(["The Open Gate", "A Place to Return"], 1):
        body = "".join(f"<p>{p}</p>" for p in paragraphs * 3)
        z.writestr(f"EPUB/chapter{i}.xhtml", f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{title}</title></head><body><h2>{title}</h2>{body}</body></html>', compress_type=ZIP_DEFLATED)
print(target)
