using Chitalka.Core;
using Xunit;
using System.Security.Cryptography;
namespace Chitalka.Tests;
public class DocumentImportTests : IDisposable
{
    readonly string root=Path.Combine(Path.GetTempPath(),"chitalka-formats-"+Guid.NewGuid());
    static string Fixture(string name)=>Path.Combine(AppContext.BaseDirectory,"Formats",name);
    [Theory][InlineData("sample.txt")][InlineData("unicode.txt")][InlineData("sample.docx")][InlineData("sample.fb2")][InlineData("sample.fb2.zip")][InlineData("sample.pdf")]
    public async Task ImportReadsTextAndKeepsOriginalHash(string name)
    {
        var store=new Store(root);var library=new EpubLibrary(store);var original=Fixture(name);var before=File.ReadAllBytes(original);var book=await library.Import(original);
        Assert.Equal(Convert.ToHexString(SHA256.HashData(before)).ToLowerInvariant(),book.Hash);Assert.Equal(before,File.ReadAllBytes(original));Assert.NotEqual("EPUB",book.SourceFormat);
        var chapters=Directory.GetFiles(Path.Combine(root,"books",book.Hash,"Text"),"*.xhtml");var text=string.Join(" ",chapters.Select(File.ReadAllText));Assert.Contains(name=="unicode.txt"?"Любопытный":"curious",text);Assert.Contains("script-src 'none'",text);
        Assert.Equal(book.Hash,(await library.Import(original)).Hash);Assert.Single(store.State.Library);
        store.SaveMoment(new("m",book.Hash,book.Title,book.Author,"{\"href\":\"Text/chapter-0001.xhtml\"}","curious","note",1,1));store.DeleteBook(book.Hash);Assert.Single(store.State.Data.Bookmarks);Assert.Equal(book.Hash,(await library.Import(original)).Hash);
    }
    [Theory][InlineData("scan.pdf")][InlineData("password.pdf")][InlineData("broken.docx")][InlineData("xxe.fb2")]
    public async Task BadOrNonTextDocumentLeavesNoBook(string name){var store=new Store(root);await Assert.ThrowsAnyAsync<Exception>(()=>new EpubLibrary(store).Import(Fixture(name)));Assert.Empty(store.State.Library);Assert.Empty(Directory.GetDirectories(Path.Combine(root,"books")));}
    [Fact] public void DocxMetadataAndPdfPagesPreserved(){var d=DocumentImporter.Extract(Fixture("sample.docx"));Assert.Equal("Format sample",d.Title);Assert.Equal("QA Writer",d.Author);Assert.Contains(d.Chapters.SelectMany(c=>c.Paragraphs),x=>x.Contains("Table cell"));Assert.Equal(2,DocumentImporter.Extract(Fixture("sample.pdf")).Chapters.Count);}
    [Fact] public async Task ImportsEpubLargerThanOneHundredMegabytes(){
        Directory.CreateDirectory(root);var file=Path.Combine(root,"large.epub");
        DocumentImporter.WriteEpub(new("Large","QA","TXT",[new("Chapter",["A curious reader."])]),"fixture",file);
        using(var zip=System.IO.Compression.ZipFile.Open(file,System.IO.Compression.ZipArchiveMode.Update)){
            using var output=zip.CreateEntry("padding.bin",System.IO.Compression.CompressionLevel.NoCompression).Open();var block=new byte[1024*1024];for(var i=0;i<101;i++)output.Write(block);
        }
        Assert.True(new FileInfo(file).Length>100L*1024*1024);var store=new Store(Path.Combine(root,"profile"));
        var book=await new EpubLibrary(store).Import(file);Assert.Equal("Large",book.Title);Assert.Single(store.State.Library);
    }
    [Fact] public void ImageOnlyDocxRequiresOcrInsteadOfGenericEmptyBookError(){var error=Assert.Throws<ScannedDocxException>(()=>DocumentImporter.Extract(Fixture("scan.docx")));Assert.Contains("OCR",error.Message);}
    public void Dispose(){Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();if(Directory.Exists(root))Directory.Delete(root,true);}
}
