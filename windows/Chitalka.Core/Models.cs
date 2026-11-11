using System.Text.Json;
using System.Text.Json.Serialization;
namespace Chitalka.Core;
public record ReadingState(string Hash, string Title, string Author, string? Locator, double Progress, long LastRead);
public record Book(string Hash, string Title, string Author, string Opf, string? Cover, long AddedAt);
public record Word(string Id, string Original, string Normalized, string Translation, string Context, string BookHash, string BookTitle, string Locator, string Source, long CreatedAt);
public record Moment(string Id, string BookHash, string BookTitle, string Author, string Locator, string Quote, string Note, long CreatedAt, long UpdatedAt, string Kind = "QUOTE");
public record Review(string WordId, int Rating, long CreatedAt);
public record Preferences(string Theme = "paper", int Font = 22, double LineHeight = 1.65, int Width = 720, string Flow = "paginated", string Sort = "recent");
public record Practice(List<string> Ids, int Index = 0, int Again = 0, int Hard = 0, int Good = 0);
public class Backup
{
    public string Format { get; set; } = "ru.chitalka.app.backup";
    public int Version { get; set; } = 1;
    public long CreatedAt { get; set; } = Clock.Now;
    public List<ReadingState> Books { get; set; } = [];
    public List<Word> Words { get; set; } = [];
    public List<Moment> Bookmarks { get; set; } = [];
    public List<Review> Reviews { get; set; } = [];
}
public class State
{
    public List<Book> Library { get; set; } = [];
    public Backup Data { get; set; } = new();
    public Preferences Settings { get; set; } = new();
    public Practice? Practice { get; set; }
}
public static class Clock { public static long Now => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); }
public static class Json
{
    public static readonly JsonSerializerOptions Options = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true, WriteIndented = true };
    public static string Encode<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T Decode<T>(string text) => JsonSerializer.Deserialize<T>(text.TrimStart('\uFEFF'), Options) ?? throw new InvalidDataException("Пустой файл данных");
}
