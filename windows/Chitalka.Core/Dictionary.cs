using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Data.Sqlite;
namespace Chitalka.Core;
public record Meaning(string Headword, string PartOfSpeech, string Translation, string Definition);
public sealed class DictionaryService(string assets, string root)
{
    readonly SemaphoreSlim gate = new(1);
    string? database;
    public const string Source = "FreeDict / WikDict · CC BY-SA 3.0";
    public async Task<List<Meaning>> Lookup(string text)
    {
        var key = Key(text); if (key.Length is 0 or > 100 || key.Any(char.IsWhiteSpace)) return [];
        await gate.WaitAsync();
        try {
            if (database == null) {
                using var manifest = JsonDocument.Parse(await File.ReadAllTextAsync(Path.Combine(assets, "manifest.json")));
                var info = manifest.RootElement; var folder = Path.Combine(root, "dictionaries"); Directory.CreateDirectory(folder);
                var file = Path.Combine(folder, info.GetProperty("version").GetString() + ".sqlite"); var expected = info.GetProperty("sha256").GetString();
                bool Valid(string p) { if (!File.Exists(p) || new FileInfo(p).Length != info.GetProperty("bytes").GetInt64()) return false; using var stream = File.OpenRead(p); return Convert.ToHexString(SHA256.HashData(stream)).Equals(expected, StringComparison.OrdinalIgnoreCase); }
                if (!Valid(file)) {
                    var temp = file + ".part";
                    try { using (var input = new GZipStream(File.OpenRead(Path.Combine(assets, "eng-rus.sqlite.gz.bin")), CompressionMode.Decompress)) using (var output = File.Create(temp)) { var buffer = new byte[32768]; long total = 0; int n; while ((n = await input.ReadAsync(buffer)) > 0) { total += n; if (total > info.GetProperty("bytes").GetInt64()) throw new InvalidDataException("Повреждён словарь"); await output.WriteAsync(buffer.AsMemory(0, n)); } }
                        if (!Valid(temp)) throw new InvalidDataException("Не совпадает контрольная сумма словаря"); File.Move(temp, file, true);
                    } finally { if (File.Exists(temp)) File.Delete(temp); }
                }
                database = file;
            }
            using var db = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = database, Mode = SqliteOpenMode.ReadOnly }.ToString()); await db.OpenAsync();
            foreach (var candidate in Candidates(key)) {
                var list = new List<Meaning>(); using var cmd = db.CreateCommand(); cmd.CommandText = "SELECT headword,pos,translation,definition FROM entry WHERE normalized=$key ORDER BY id LIMIT 80"; cmd.Parameters.AddWithValue("$key", candidate);
                using var reader = await cmd.ExecuteReaderAsync(); while (await reader.ReadAsync()) {
                    var pos = reader.GetString(1); if (candidate != key && !Irregular.ContainsKey(key) && pos is not ("v" or "n" or "adj")) continue;
                    list.Add(new(reader.GetString(0), pos, reader.GetString(2), reader.GetString(3)));
                }
                if (list.Count > 0) return list.DistinctBy(x => (x.Headword, x.PartOfSpeech, x.Translation)).ToList();
            }
            return [];
        } finally { gate.Release(); }
    }
    public static string Key(string text) => text.Normalize(NormalizationForm.FormC).Trim().Trim(' ', '.', ',', '!', '?', ';', ':', '"', '“', '”', '(', ')').Replace('’', '\'').Replace('‐', '-').Replace('‑', '-').ToLowerInvariant();
    static readonly Dictionary<string, string> Irregular = new(StringComparer.Ordinal) { ["went"]="go", ["gone"]="go", ["children"]="child", ["men"]="man", ["women"]="woman", ["mice"]="mouse", ["feet"]="foot", ["teeth"]="tooth", ["was"]="be", ["were"]="be", ["been"]="be", ["said"]="say", ["took"]="take", ["taken"]="take", ["saw"]="see", ["seen"]="see", ["thought"]="think", ["bought"]="buy", ["brought"]="bring", ["wrote"]="write", ["written"]="write", ["ran"]="run", ["had"]="have", ["did"]="do", ["done"]="do", ["better"]="good", ["best"]="good", ["knew"]="know", ["known"]="know", ["heard"]="hear", ["felt"]="feel", ["left"]="leave", ["found"]="find", ["gave"]="give", ["given"]="give", ["made"]="make", ["came"]="come" };
    public static IEnumerable<string> Candidates(string text)
    {
        var key = Key(text); var list = new List<string> { key }; if (Irregular.TryGetValue(key, out var stem)) list.Add(stem);
        if (key.EndsWith("'s")) list.Add(key[..^2]);
        if (key.Length > 4 && key.EndsWith("ies")) list.Add(key[..^3] + "y");
        if (key.Length > 3 && key.EndsWith('s') && !key.EndsWith("ss")) { list.Add(key[..^1]); if (key.EndsWith("es")) list.Add(key[..^2]); }
        foreach (var suffix in new[] { "ing", "ed" }) if (key.EndsWith(suffix) && key.Length > suffix.Length + 2) { var s = key[..^suffix.Length]; list.Add(s); list.Add(s + "e"); if (s.Length >= 2 && s[^1] == s[^2]) list.Add(s[..^1]); if (suffix == "ed" && s.EndsWith('i')) list.Add(s[..^1] + "y"); }
        return list.Distinct();
    }
}
