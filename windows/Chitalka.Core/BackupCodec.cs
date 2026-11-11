using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
namespace Chitalka.Core;
public static partial class BackupCodec
{
    public const int MaxBytes = 16 * 1024 * 1024;
    static void Need(bool ok, string error) { if (!ok) throw new InvalidDataException(error); }
    static void Text(string? value, bool required = false, int max = MaxBytes) => Need(value != null && value.Length <= max && (!required || !string.IsNullOrWhiteSpace(value)), "Некорректное текстовое поле");
    static void Hash(string? value) => Need(value != null && HashPattern().IsMatch(value), "Некорректный хеш книги");
    [GeneratedRegex("^[0-9a-f]{64}$")] private static partial Regex HashPattern();
    public static void CheckLocator(string? text)
    {
        Text(text, true); using var doc = JsonDocument.Parse(text!);
        Need(doc.RootElement.TryGetProperty("href", out var href) && href.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(href.GetString()), "Некорректное место в книге");
    }
    public static Backup Decode(string text)
    {
        Need(Encoding.UTF8.GetByteCount(text) <= MaxBytes, "Копия превышает 16 МБ");
        var b = Json.Decode<Backup>(text);
        // Do not accept missing top-level fields through DTO defaults.
        using var raw = JsonDocument.Parse(text.TrimStart('\uFEFF'));
        foreach (var key in new[] { "format", "version", "books", "words", "bookmarks", "reviews" }) Need(raw.RootElement.TryGetProperty(key, out _), "Копия имеет неполную структуру");
        Need(b.Format == "ru.chitalka.app.backup" && b.Version == 1, "Это не поддерживаемая резервная копия Читалки");
        Need(b.Books != null && b.Words != null && b.Bookmarks != null && b.Reviews != null, "Повреждены списки копии");
        Need((long)b.Books!.Count + b.Words!.Count + b.Bookmarks!.Count + b.Reviews!.Count <= 100000, "Слишком много записей");
        foreach (var x in b.Books) { Hash(x.Hash); Text(x.Title); Text(x.Author); if (x.Locator != null) CheckLocator(x.Locator); Need(double.IsFinite(x.Progress) && x.Progress is >= 0 and <= 1 && x.LastRead >= 0, "Некорректный прогресс"); }
        foreach (var w in b.Words) { Text(w.Id, true, 128); Text(w.Original, true); Text(w.Normalized, true); Text(w.Translation, true); Text(w.Context); Hash(w.BookHash); Text(w.BookTitle); CheckLocator(w.Locator); Text(w.Source); Need(w.CreatedAt >= 0, "Некорректная дата слова"); }
        foreach (var m in b.Bookmarks) { Text(m.Id, true, 128); Hash(m.BookHash); Text(m.BookTitle); Text(m.Author); CheckLocator(m.Locator); Text(m.Quote, true); Text(m.Note); Need(m.CreatedAt >= 0 && m.UpdatedAt >= 0 && m.Kind is "QUOTE" or "BOOKMARK", "Некорректный момент"); }
        Need(b.Books.Select(x => x.Hash).Distinct().Count() == b.Books.Count && b.Words.Select(x => x.Id).Distinct().Count() == b.Words.Count && b.Bookmarks.Select(x => x.Id).Distinct().Count() == b.Bookmarks.Count, "Повторяющиеся идентификаторы");
        var ids = b.Words.Select(x => x.Id).ToHashSet();
        foreach (var r in b.Reviews) Need(ids.Contains(r.WordId) && r.Rating is >= 0 and <= 2 && r.CreatedAt >= 0, "Некорректная история повторений");
        return b;
    }
    public static string Encode(Backup backup) { var result = Json.Encode(backup); Decode(result); return result; }
    public static string Csv(Backup backup)
    {
        static string Cell(string value) { if (value.TrimStart().FirstOrDefault() is '=' or '+' or '-' or '@') value = "'" + value; return "\"" + value.Replace("\"", "\"\"") + "\""; }
        var rows = new List<string> { "word,translation,context,book,source,addedAt" };
        rows.AddRange(backup.Words.Select(w => string.Join(",", new[] { w.Original, w.Translation, w.Context, w.BookTitle, w.Source, w.CreatedAt.ToString() }.Select(Cell))));
        return "\uFEFF" + string.Join("\r\n", rows) + "\r\n";
    }
    public static string Restore(Store store, string text)
    {
        var input = Decode(text); var previous = Encode(store.State.Data);
        var folder = Path.Combine(store.Root, "backups"); Directory.CreateDirectory(folder);
        File.WriteAllText(Path.Combine(folder, $"before-import-{Clock.Now}-{Guid.NewGuid():N}.json"), previous, new UTF8Encoding(false));
        var words = 0; var marks = 0; var reviews = 0;
        store.Change(s => {
            foreach (var b in input.Books) { var local = s.Data.Books.FirstOrDefault(x => x.Hash == b.Hash); if (local == null || b.LastRead > local.LastRead) { s.Data.Books.RemoveAll(x => x.Hash == b.Hash); s.Data.Books.Add(b); } }
            var mapping = new Dictionary<string, string>();
            foreach (var w in input.Words) {
                var local = s.Data.Words.FirstOrDefault(x => x.Normalized == w.Normalized && x.Translation == w.Translation);
                if (local != null) { mapping[w.Id] = local.Id; continue; }
                var id = s.Data.Words.Any(x => x.Id == w.Id) ? Guid.NewGuid().ToString() : w.Id;
                s.Data.Words.Add(w with { Id = id }); mapping[w.Id] = id; words++;
            }
            foreach (var m in input.Bookmarks) {
                var old = s.Data.Bookmarks.FirstOrDefault(x => x.BookHash == m.BookHash && x.Locator == m.Locator);
                if (old == null) { s.Data.Bookmarks.Add(m with { Id = s.Data.Bookmarks.Any(x => x.Id == m.Id) ? Guid.NewGuid().ToString() : m.Id }); marks++; }
                else if (Math.Max(m.UpdatedAt, m.CreatedAt) > Math.Max(old.UpdatedAt, old.CreatedAt)) { s.Data.Bookmarks.Remove(old); s.Data.Bookmarks.Add(m with { Id = old.Id }); }
            }
            foreach (var r in input.Reviews) { var item = r with { WordId = mapping[r.WordId] }; if (!s.Data.Reviews.Contains(item)) { s.Data.Reviews.Add(item); reviews++; } }
        });
        foreach (var old in new DirectoryInfo(folder).GetFiles("before-import-*.json").OrderByDescending(x => x.LastWriteTimeUtc).Skip(3)) old.Delete();
        return $"Добавлено слов: {words}, моментов: {marks}, повторений: {reviews}. Для переходов добавьте исходные EPUB.";
    }
}
