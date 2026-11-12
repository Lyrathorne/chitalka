"""Synthetic image-only DOCX regression fixture; no user book content."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import io,zipfile
image=Image.new('RGB',(1400,900),'white');draw=ImageDraw.Draw(image)
font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',56)
draw.text((80,130),'The quiet garden',fill='black',font=font)
draw.text((80,260),'Alice was curious about the book.',fill='black',font=font)
buffer=io.BytesIO();image.save(buffer,format='PNG')
path=Path(__file__).resolve().parents[1]/'testdata/formats/scan.docx'
with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED) as z:
    z.writestr('word/document.xml','<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body><w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p></w:body></w:document>')
    z.writestr('word/_rels/document.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/page.png"/></Relationships>')
    z.writestr('word/media/page.png',buffer.getvalue())
print(path,path.stat().st_size)
