"""Copy only the supplied project roadmap into public docs; never scan a vault."""
from pathlib import Path
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
args = parser.parse_args()
content = args.source.read_text(encoding="utf-8-sig")
content = "\n".join(line for line in content.splitlines() if not line.startswith("> Исходная идея:")).rstrip() + "\n"
target = Path(__file__).resolve().parents[1] / "docs/ROADMAP.md"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(content, encoding="utf-8")
print(f"Roadmap synced: {sum(line.startswith('- [x]') for line in content.splitlines())} completed items")
