"""Sync only the dedicated Windows plan; never alters the Android roadmap."""
from pathlib import Path
import argparse
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser()
p.add_argument('--vault',default='C:/Users/Gleb/Documents/хранилище/Хранилище')
p.add_argument('--check',action='append',default=[])
a=p.parse_args()
plan=root/'docs/WINDOWS-PLAN.md'
text=plan.read_text(encoding='utf8')
for prefix in a.check:
    matches=[line for line in text.splitlines() if line.startswith('- [ ] '+prefix)]
    if len(matches)!=1:raise RuntimeError('Expected one unchecked item: '+prefix)
    text=text.replace(matches[0],matches[0].replace('- [ ]','- [x]',1))
plan.write_text(text,encoding='utf8')
vault=Path(a.vault)
if vault.is_dir():(vault/'Читалка Windows — план разработки.md').write_text(text,encoding='utf8')
print('Checked',text.count('- [x]'),'of',text.count('- [x]')+text.count('- [ ]'))
