using Microsoft.Data.Sqlite;
namespace Chitalka.Core;
public sealed class Store
{
    public string Root { get; }
    public State State { get; private set; }
    public Store(string root)
    {
        Root = Path.GetFullPath(root); Directory.CreateDirectory(Root);
        using var db = Open();
        using var command = db.CreateCommand();
        command.CommandText = "PRAGMA user_version";
        if (Convert.ToInt32(command.ExecuteScalar()) > 1) throw new InvalidDataException("Данные созданы более новой версией Читалки. Обновите приложение.");
        command.CommandText = "PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS app_state(id INTEGER PRIMARY KEY CHECK(id=1), json TEXT NOT NULL); PRAGMA user_version=1;";
        command.ExecuteNonQuery(); command.CommandText = "SELECT json FROM app_state WHERE id=1";
        State = command.ExecuteScalar() is string value ? Json.Decode<State>(value) : new();
    }
    SqliteConnection Open() { var db = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = Path.Combine(Root, "library.sqlite") }.ToString()); db.Open(); return db; }
    public void Change(Action<State> edit)
    {
        var next = Json.Decode<State>(Json.Encode(State)); edit(next);
        using var db = Open(); using var tx = db.BeginTransaction(); using var cmd = db.CreateCommand(); cmd.Transaction = tx;
        cmd.CommandText = "INSERT INTO app_state(id,json) VALUES(1,$json) ON CONFLICT(id) DO UPDATE SET json=excluded.json";
        cmd.Parameters.AddWithValue("$json", Json.Encode(next)); cmd.ExecuteNonQuery(); tx.Commit(); State = next;
    }
    public void Position(string hash, string locator, double progress)
    {
        BackupCodec.CheckLocator(locator);
        var book = State.Library.First(x => x.Hash == hash);
        Change(s => { s.Data.Books.RemoveAll(x => x.Hash == hash); s.Data.Books.Add(new(hash, book.Title, book.Author, locator, Math.Clamp(progress, 0, 1), Clock.Now)); });
    }
    public void DeleteBook(string hash)
    {
        if (hash.Length != 64 || hash.Any(c => !char.IsAsciiHexDigit(c))) throw new InvalidDataException("Некорректный хеш книги");
        Change(s => s.Library.RemoveAll(x => x.Hash == hash));
        var unpacked = EpubLibrary.SafePath(Path.Combine(Root,"books"),hash);
        if (Directory.Exists(unpacked)) Directory.Delete(unpacked,true);
        foreach(var extension in new[]{".epub",".pdf",".docx",".fb2",".txt",".zip"}) { var original = EpubLibrary.SafePath(Path.Combine(Root,"originals"),hash+extension);if (File.Exists(original)) File.Delete(original); }
    }
    public string AddWord(Word word)
    {
        var duplicate = State.Data.Words.FirstOrDefault(x => x.Normalized == word.Normalized && x.Translation == word.Translation);
        if (duplicate != null) return duplicate.Id;
        Change(s => s.Data.Words.Add(word)); return word.Id;
    }
    public void SaveMoment(Moment mark)
    {
        var old = State.Data.Bookmarks.FirstOrDefault(x => x.Id == mark.Id || (x.BookHash == mark.BookHash && x.Locator == mark.Locator));
        Change(s => { if (old != null) s.Data.Bookmarks.RemoveAll(x => x.Id == old.Id); s.Data.Bookmarks.Add(mark with { Id = old?.Id ?? mark.Id, CreatedAt = old?.CreatedAt ?? mark.CreatedAt, UpdatedAt = Clock.Now }); });
    }
    public void BeginPractice()
    {
        if (State.Practice is { } p && p.Index < p.Ids.Count) return;
        Change(s => s.Practice = new(s.Data.Words.OrderBy(_ => Random.Shared.Next()).Select(x => x.Id).ToList()));
    }
    public void Rate(int rating)
    {
        if (rating is < 0 or > 2) throw new InvalidDataException("Неизвестная оценка");
        Change(s => { var p = s.Practice ?? throw new InvalidOperationException("Сессия не начата");
            if (p.Index >= p.Ids.Count) return;
            if (s.Data.Words.Any(w => w.Id == p.Ids[p.Index])) s.Data.Reviews.Add(new(p.Ids[p.Index], rating, Clock.Now));
            s.Practice = p with { Index = p.Index + 1, Again = p.Again + (rating == 0 ? 1 : 0), Hard = p.Hard + (rating == 1 ? 1 : 0), Good = p.Good + (rating == 2 ? 1 : 0) }; });
    }
}
