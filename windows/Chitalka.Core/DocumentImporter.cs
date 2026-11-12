using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml;
using System.Xml.Linq;
using UglyToad.PdfPig;
using UglyToad.PdfPig.DocumentLayoutAnalysis.TextExtractor;
namespace Chitalka.Core;
public record TextChapter(string Title,List<string> Paragraphs);
public record ImportedText(string Title,string Author,string Format,List<TextChapter> Chapters)
{
    public string Notice => Format=="PDF" ? "PDF открыт как текст: расположение колонок и таблиц может отличаться. OCR не выполняется." : $"{Format} открыт в текстовом режиме: сложное оформление и изображения не переносятся.";
}
public static class DocumentImporter
{
    static PdfDocument OpenPdf(string source) { try { return PdfDocument.Open(source); } catch(UglyToad.PdfPig.Exceptions.PdfDocumentEncryptedException ex) { throw new InvalidDataException("PDF защищён паролем. Сохраните копию без пароля.",ex); } }
    public const string Formats="EPUB, PDF, DOCX, FB2, FB2.ZIP, TXT";
    public const int MaxText=8_000_000,MaxXml=32*1024*1024,MaxPages=1000;
    public static bool Supported(string path)=>new[]{".epub",".pdf",".docx",".fb2",".txt",".zip"}.Contains(Path.GetExtension(path).ToLowerInvariant());
    static string Clean(string value)=>Regex.Replace(value.Replace("\r\n","\n").Replace('\r','\n'),"[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","").Trim();
    static string Flat(string value)=>Regex.Replace(Clean(value),"\\s+"," ").Trim();
    static byte[] Entry(ZipArchive zip,string name){var e=zip.GetEntry(name)??throw new InvalidDataException("В документе отсутствует "+name);if(e.Length>MaxXml)throw new InvalidDataException("XML документа превышает 32 МБ");using var input=e.Open();using var output=new MemoryStream();var buffer=new byte[32768];int n;while((n=input.Read(buffer))>0){if(output.Length+n>MaxXml)throw new InvalidDataException("XML документа превышает 32 МБ");output.Write(buffer,0,n);}return output.ToArray();}
    static XDocument Xml(byte[] bytes){using var input=new MemoryStream(bytes);return XDocument.Load(XmlReader.Create(input,new XmlReaderSettings{DtdProcessing=DtdProcessing.Prohibit,XmlResolver=null,MaxCharactersInDocument=MaxXml}));}
    static void CheckZip(ZipArchive zip){if(zip.Entries.Count>10000||zip.Entries.Sum(x=>x.Length)>EpubLibrary.MaxUnpacked)throw new InvalidDataException("Документ превышает предел распаковки (300 МБ / 10 000 файлов)");}
    static string Text(XElement p)=>Clean(string.Concat(p.Descendants().Where(x=>!x.Ancestors().Any(a=>a.Name.LocalName=="del")).Select(x=>x.Name.LocalName switch{"t"=>x.Value,"tab"=>" ","br" or "cr"=>"\n",_=>""})));
    static List<TextChapter> Chapters(IEnumerable<string> paragraphs,string title)
    {
        var chapters=new List<TextChapter>();var block=new List<string>();int count=0,total=0;
        foreach(var raw in paragraphs){var text=Clean(raw);for(int i=0;i<text.Length;i+=100000){var piece=text.Substring(i,Math.Min(100000,text.Length-i));total+=piece.Length;if(total>MaxText)throw new InvalidDataException("В документе больше 8 миллионов символов");if(count+piece.Length>100000&&block.Count>0){chapters.Add(new($"Часть {chapters.Count+1}",block));block=[];count=0;}block.Add(piece);count+=piece.Length;}}
        if(block.Count>0)chapters.Add(new(chapters.Count==0?title:$"Часть {chapters.Count+1}",block));return chapters;
    }
    public static ImportedText Extract(string source)
    {
        var ext=Path.GetExtension(source).ToLowerInvariant();var title=Path.GetFileNameWithoutExtension(source);var author="";List<TextChapter> chapters;
        if(ext==".pdf"){
            using var pdf=OpenPdf(source);if(pdf.IsEncrypted)throw new InvalidDataException("PDF с шифрованием или паролем не поддерживается");if(pdf.NumberOfPages>MaxPages)throw new InvalidDataException("В PDF больше 1000 страниц");
            title=string.IsNullOrWhiteSpace(pdf.Information.Title)?title:pdf.Information.Title;author=pdf.Information.Author??"";chapters=[];int total=0;
            foreach(var page in pdf.GetPages()){var text=Flat(ContentOrderTextExtractor.GetText(page));total+=text.Length;if(total>MaxText)throw new InvalidDataException("В PDF слишком много текста");if(text.Length>0)chapters.Add(new($"Страница {page.Number}",Enumerable.Range(0,(text.Length+99999)/100000).Select(i=>text.Substring(i*100000,Math.Min(100000,text.Length-i*100000))).ToList()));}
            if(chapters.Sum(x=>x.Paragraphs.Sum(p=>p.Length))<10)throw new InvalidDataException("В PDF нет доступного текстового слоя. Это скан: сначала распознайте текст (OCR).");
            return new(title,author,"PDF",chapters);
        }
        if(ext==".docx"){
            using var zip=ZipFile.OpenRead(source);CheckZip(zip);var doc=Xml(Entry(zip,"word/document.xml"));
            if(zip.GetEntry("docProps/core.xml")!=null){var meta=Xml(Entry(zip,"docProps/core.xml"));title=meta.Descendants().FirstOrDefault(x=>x.Name.LocalName=="title")?.Value.Trim() is {Length:>0} t?t:title;author=meta.Descendants().FirstOrDefault(x=>x.Name.LocalName=="creator")?.Value??"";}
            chapters=Chapters(doc.Descendants().Where(x=>x.Name.LocalName=="p").Select(Text),title);
        }else if(ext is ".fb2" or ".zip"){
            byte[] bytes;
            if(ext==".zip"){using var zip=ZipFile.OpenRead(source);CheckZip(zip);var entries=zip.Entries.Where(x=>x.FullName.EndsWith(".fb2",StringComparison.OrdinalIgnoreCase)).ToList();if(entries.Count!=1)throw new InvalidDataException("ZIP должен содержать ровно одну книгу FB2");bytes=Entry(zip,entries[0].FullName);}else{if(new FileInfo(source).Length>MaxXml)throw new InvalidDataException("FB2 превышает 32 МБ");bytes=File.ReadAllBytes(source);}
            var doc=Xml(bytes);if(doc.Root?.Name.LocalName!="FictionBook")throw new InvalidDataException("Это не FB2");
            var info=doc.Descendants().FirstOrDefault(x=>x.Name.LocalName=="title-info");title=info?.Elements().FirstOrDefault(x=>x.Name.LocalName=="book-title")?.Value.Trim() is {Length:>0} t?t:title;
            author=string.Join(", ",info?.Elements().Where(x=>x.Name.LocalName=="author").Select(x=>string.Join(" ",x.Elements().Where(e=>e.Name.LocalName is "first-name" or "middle-name" or "last-name").Select(e=>e.Value.Trim())))??[]);
            chapters=Chapters(doc.Root.Elements().Where(x=>x.Name.LocalName=="body").SelectMany(x=>x.Descendants()).Where(x=>x.Name.LocalName is "p" or "v" or "subtitle").Select(x=>x.Value),title);
        }else if(ext==".txt"){
            var bytes=File.ReadAllBytes(source);if(bytes.Length>32*1024*1024)throw new InvalidDataException("TXT превышает 32 МБ");string text;
            if(bytes.Length>=2&&((bytes[0]==255&&bytes[1]==254)||(bytes[0]==254&&bytes[1]==255))){using var input=new StreamReader(new MemoryStream(bytes),Encoding.UTF8,true);text=input.ReadToEnd();}
            else {try{text=new UTF8Encoding(false,true).GetString(bytes).TrimStart('\uFEFF');}catch(DecoderFallbackException){Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);text=Encoding.GetEncoding(1251).GetString(bytes);}}
            if(text.Any(c=>c=='\0'))throw new InvalidDataException("Файл не похож на TXT");chapters=Chapters(Regex.Split(text,"\\r?\\n\\s*\\r?\\n"),title);
        }else throw new InvalidDataException("Поддерживаются "+Formats+". Старый DOC сохраните как DOCX.");
        if(chapters.Count==0)throw new InvalidDataException("В документе нет текста для чтения");return new(title,author,ext==".zip"?"FB2":ext.TrimStart('.').ToUpperInvariant(),chapters);
    }
    public static void WriteEpub(ImportedText book,string hash,string destination)
    {
        static string E(string s)=>System.Security.SecurityElement.Escape(Clean(s))??"";
        using var zip=ZipFile.Open(destination,ZipArchiveMode.Create);
        void Put(string path,string text,bool stored=false){var entry=zip.CreateEntry(path,stored?CompressionLevel.NoCompression:CompressionLevel.Optimal);using var w=new StreamWriter(entry.Open(),new UTF8Encoding(false));w.Write(text);}
        Put("mimetype","application/epub+zip",true);
        Put("META-INF/container.xml","<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
        string PathFor(int i)=>$"Text/chapter-{i+1:D4}.xhtml";
        string Html(string title,string body)=>$"<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>{E(title)}</title></head><body>{body}</body></html>";
        Put("nav.xhtml",Html("Содержание","<nav epub:type=\"toc\"><h1>Содержание</h1><ol>"+string.Concat(book.Chapters.Select((c,i)=>$"<li><a href=\"{PathFor(i)}\">{E(c.Title)}</a></li>"))+"</ol></nav>"));
        Put("content.opf",$"<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"uid\">urn:sha256:{hash}</dc:identifier><dc:title>{E(book.Title)}</dc:title><dc:creator>{E(book.Author)}</dc:creator><dc:language>und</dc:language><dc:description>text-import-v1 · {E(book.Notice)}</dc:description><meta property=\"dcterms:modified\">2026-11-11T00:00:00Z</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"+string.Concat(book.Chapters.Select((c,i)=>$"<item id=\"c{i}\" href=\"{PathFor(i)}\" media-type=\"application/xhtml+xml\"/>"))+"</manifest><spine>"+string.Concat(book.Chapters.Select((c,i)=>$"<itemref idref=\"c{i}\"/>"))+"</spine></package>");
        for(int i=0;i<book.Chapters.Count;i++){var c=book.Chapters[i];Put(PathFor(i),Html(c.Title,$"<h1>{E(c.Title)}</h1>"+string.Concat(c.Paragraphs.Select(p=>"<p>"+E(p).Replace("\n","<br/>")+"</p>"))));}
    }
}
