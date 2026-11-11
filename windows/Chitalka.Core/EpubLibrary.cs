using System.IO.Compression;
using System.Security.Cryptography;
using System.Xml;
using System.Xml.Linq;
namespace Chitalka.Core;
public sealed class EpubLibrary(Store store)
{
    public const long MaxFile = 100L * 1024 * 1024, MaxUnpacked = 300L * 1024 * 1024, MaxChapter = 8L * 1024 * 1024;
    public static XDocument Xml(Stream input) => XDocument.Load(XmlReader.Create(input, new XmlReaderSettings { DtdProcessing = DtdProcessing.Ignore, XmlResolver = null, MaxCharactersInDocument = MaxChapter, IgnoreWhitespace = false }), LoadOptions.PreserveWhitespace);
    public static string SafePath(string root, string relative)
    {
        relative = Uri.UnescapeDataString(relative).Replace('\\', '/');
        if (relative.StartsWith('/') || relative.Contains(':') || relative.Contains('\0')) throw new InvalidDataException("Недопустимый путь EPUB");
        var full = Path.GetFullPath(Path.Combine(root, relative)); var prefix = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!full.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Путь EPUB выходит за пределы книги");
        return full;
    }
    public async Task<Book> Import(string path)
    {
        var source = new FileInfo(path);
        if (!source.Exists || source.Length > MaxFile || source.Length == 0) throw new InvalidDataException("Выберите EPUB размером до 100 МБ");
        string hash; using (var input = source.OpenRead()) hash = Convert.ToHexString(await SHA256.HashDataAsync(input)).ToLowerInvariant();
        var existing = store.State.Library.FirstOrDefault(x => x.Hash == hash); if (existing != null) return existing;
        var web = Path.Combine(store.Root, "books"); Directory.CreateDirectory(web);
        var target = Path.Combine(web, hash); var temp = Path.Combine(web, ".import-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(temp);
        try {
            using var zip = ZipFile.OpenRead(path);
            if (zip.Entries.Count > 10000) throw new InvalidDataException("В EPUB больше 10 000 файлов");
            long total = 0; var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var entry in zip.Entries) {
                total = checked(total + entry.Length); if (total > MaxUnpacked) throw new InvalidDataException("Распакованный EPUB больше 300 МБ");
                var destination = SafePath(temp, entry.FullName);
                if (!paths.Add(destination) || ((entry.ExternalAttributes >> 16) & 0xF000) == 0xA000) throw new InvalidDataException("Неоднозначный или небезопасный путь EPUB");
                if (entry.FullName.EndsWith('/')) { Directory.CreateDirectory(destination); continue; }
                var ext = Path.GetExtension(destination).ToLowerInvariant();
                if (ext is ".html" or ".htm" or ".xhtml" or ".svg" or ".xml" or ".opf" or ".ncx" && entry.Length > MaxChapter) throw new InvalidDataException("Глава EPUB превышает 8 МБ");
                Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                using var input = entry.Open(); using var output = File.Create(destination); await input.CopyToAsync(output);
            }
            if (File.Exists(Path.Combine(temp, "META-INF", "encryption.xml"))) {
                using var enc = File.OpenRead(Path.Combine(temp, "META-INF", "encryption.xml"));
                if (Xml(enc).Descendants().Any(x => x.Name.LocalName == "EncryptionMethod" && !((string?)x.Attribute("Algorithm") ?? "").Contains("embedding"))) throw new InvalidDataException("EPUB с DRM не поддерживается");
            }
            using var containerInput = File.OpenRead(Path.Combine(temp, "META-INF", "container.xml"));
            var opfRelative = Xml(containerInput).Descendants().FirstOrDefault(x => x.Name.LocalName == "rootfile")?.Attribute("full-path")?.Value ?? throw new InvalidDataException("Не найдено содержимое EPUB");
            containerInput.Close();
            var opfPath = SafePath(temp, opfRelative); using var opfInput = File.OpenRead(opfPath); var opf = Xml(opfInput);
            opfInput.Close();
            string Meta(string key) => opf.Descendants().FirstOrDefault(x => x.Name.LocalName == key)?.Value.Trim() ?? "";
            var items = opf.Descendants().Where(x => x.Name.LocalName == "item").ToList();
            if (!opf.Descendants().Any(x => x.Name.LocalName == "itemref")) throw new InvalidDataException("В EPUB нет глав для чтения");
            var coverId = opf.Descendants().FirstOrDefault(x => x.Name.LocalName == "meta" && (string?)x.Attribute("name") == "cover")?.Attribute("content")?.Value;
            var coverItem = items.FirstOrDefault(x => ((string?)x.Attribute("properties") ?? "").Split(' ').Contains("cover-image")) ?? items.FirstOrDefault(x => (string?)x.Attribute("id") == coverId);
            string? cover = null;
            if (coverItem?.Attribute("href") is { } c) { var candidate = SafePath(Path.GetDirectoryName(opfPath)!, c.Value); SafePath(temp, Path.GetRelativePath(temp, candidate)); if (File.Exists(candidate)) cover = Path.GetRelativePath(temp, candidate).Replace('\\', '/'); }
            foreach (var file in Directory.EnumerateFiles(temp, "*", SearchOption.AllDirectories)) {
                if (Path.GetExtension(file).ToLowerInvariant() is ".html" or ".htm" or ".xhtml" or ".svg") {
                    using var input = File.OpenRead(file); var doc = Xml(input); input.Close(); Sanitize(doc); await File.WriteAllTextAsync(file, doc.ToString(SaveOptions.DisableFormatting));
                }
            }
            var book = new Book(hash, string.IsNullOrWhiteSpace(Meta("title")) ? Path.GetFileNameWithoutExtension(path) : Meta("title"), Meta("creator"), opfRelative.Replace('\\', '/'), cover, Clock.Now);
            if (Directory.Exists(target)) Directory.Delete(target, true);
            Directory.Move(temp, target);
            var originals = Path.Combine(store.Root, "originals"); Directory.CreateDirectory(originals); File.Copy(path, Path.Combine(originals, hash + ".epub"), true);
            store.Change(s => s.Library.Add(book)); return book;
        } finally { if (Directory.Exists(temp)) Directory.Delete(temp, true); }
    }
    public static void Sanitize(XDocument doc)
    {
        doc.DocumentType?.Remove();
        foreach (var e in doc.Descendants().ToList()) {
            var name = e.Name.LocalName.ToLowerInvariant();
            if (name is "script" or "iframe" or "object" or "embed" or "base" or "foreignobject" or "form" || (name == "meta" && e.Attributes().Any(a => a.Name.LocalName.Equals("http-equiv", StringComparison.OrdinalIgnoreCase)))) { e.Remove(); continue; }
            foreach (var a in e.Attributes().ToList()) {
                var key = a.Name.LocalName.ToLowerInvariant(); var value = a.Value.Trim();
                if (key.StartsWith("on") || key is "srcdoc" || (key is "href" or "src" or "action" && (value.StartsWith("javascript:", StringComparison.OrdinalIgnoreCase) || value.StartsWith("vbscript:", StringComparison.OrdinalIgnoreCase) || value.StartsWith("data:text/html", StringComparison.OrdinalIgnoreCase)))) a.Remove();
            }
        }
        var head = doc.Descendants().FirstOrDefault(x => x.Name.LocalName == "head");
        if (head != null) head.AddFirst(new XElement(head.Name.Namespace + "meta", new XAttribute("http-equiv", "Content-Security-Policy"), new XAttribute("content", "default-src 'none'; script-src 'none'; style-src 'self' https://books.chitalka.local 'unsafe-inline'; img-src 'self' https://books.chitalka.local data: blob:; font-src 'self' https://books.chitalka.local data:; media-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'")));
    }
}
