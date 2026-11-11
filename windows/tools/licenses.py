"""Build the offline notices without dropping licenses from vendored runtime packages."""
from pathlib import Path
root=Path(__file__).resolve().parents[2]
sources=[root/'windows/LICENSES.md',root/'windows/Chitalka.Windows/Web/vendor/EPUBJS-LICENSE.txt',root/'windows/Chitalka.Windows/Web/vendor/JSZIP-LICENSE.txt',root/'app/src/main/assets/licenses/APACHE-2.0.txt']
runtime=root/'outputs/windows/translator-runtime'
sources += sorted(p for p in runtime.rglob('*') if p.is_file() and ('license' in p.name.lower() or 'copying' in p.name.lower()) and p.suffix.lower() in ('.txt','.md',''))
model=root/'outputs/windows/opus-original/LICENSE'
if model.exists():sources.append(model)
chunks=[]
for p in sources:
    text=p.read_text(encoding='utf8',errors='replace')
    chunks.append('\n\n'+str(p.relative_to(root))+'\n'+'='*50+'\n'+text)
text='Читалка для Windows — источники и лицензии\n'+''.join(chunks)
(root/'windows/Chitalka.Windows/Web/licenses.txt').write_text(text,encoding='utf8')
print(len(text),'characters')
