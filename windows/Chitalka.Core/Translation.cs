using System.Diagnostics;
using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
namespace Chitalka.Core;
public sealed class TranslationService(string root, string applicationFolder) : IDisposable
{
    readonly HttpClient http = new() { Timeout = TimeSpan.FromMinutes(15) };
    readonly SemaphoreSlim modelGate = new(1);
    CancellationTokenSource? download;
    public bool Downloading => download != null;
    public string ModelFolder => Path.Combine(root, "models", "opus-en-ru-int8-v1");
    public bool Ready => File.Exists(Path.Combine(ModelFolder, ".verified"));
    public object Status => new { ready = Ready, downloading = Downloading, source = "OPUS-MT EN→RU · CTranslate2 · CPU", bytes = Manifest().GetProperty("bytes").GetInt64() };
    JsonElement Manifest() => JsonDocument.Parse(File.ReadAllText(Path.Combine(applicationFolder, "Translator", "model-manifest.json"))).RootElement.Clone();
    public void CancelDownload() => download?.Cancel();
    public async Task Download(Action<long, long> progress)
    {
        if (Downloading) throw new InvalidOperationException("Загрузка уже идёт");
        using var cancel = new CancellationTokenSource(); download = cancel;
        var folder = Path.Combine(root, "models"); Directory.CreateDirectory(folder); var temp = Path.Combine(folder, "download-" + Guid.NewGuid().ToString("N") + ".part");
        try {
            var m = Manifest(); var size = m.GetProperty("bytes").GetInt64();
            using (var response = await http.GetAsync(m.GetProperty("url").GetString(), HttpCompletionOption.ResponseHeadersRead, cancel.Token)) {
                response.EnsureSuccessStatusCode(); using var input = await response.Content.ReadAsStreamAsync(cancel.Token); using var output = File.Create(temp);
                var buffer = new byte[65536]; long count = 0; int n;
                while ((n = await input.ReadAsync(buffer, cancel.Token)) > 0) { count += n; if (count > size) throw new InvalidDataException("Размер модели не совпадает"); await output.WriteAsync(buffer.AsMemory(0, n), cancel.Token); progress(count, size); }
                if (count != size) throw new InvalidDataException("Модель скачана не полностью. Повторите загрузку.");
            }
            await Install(temp, cancel.Token);
        } finally { download = null; if (File.Exists(temp)) File.Delete(temp); }
    }
    public async Task Install(string file, CancellationToken cancellation = default)
    {
        await modelGate.WaitAsync(cancellation);
        var stage = ModelFolder + "." + Guid.NewGuid().ToString("N") + ".part";
        try {
            var m = Manifest(); using (var input = File.OpenRead(file)) {
                if (input.Length != m.GetProperty("bytes").GetInt64() || !Convert.ToHexString(await SHA256.HashDataAsync(input, cancellation)).Equals(m.GetProperty("sha256").GetString(), StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Не совпадает контрольная сумма модели");
            }
            Directory.CreateDirectory(stage); using var zip = ZipFile.OpenRead(file); long total = 0;
            foreach (var entry in zip.Entries) { cancellation.ThrowIfCancellationRequested(); total += entry.Length; if (total > 300L * 1024 * 1024 || zip.Entries.Count > 100) throw new InvalidDataException("Повреждён пакет модели");
                var target = EpubLibrary.SafePath(stage, entry.FullName); if (entry.FullName.EndsWith('/')) { Directory.CreateDirectory(target); continue; } Directory.CreateDirectory(Path.GetDirectoryName(target)!); using var input = entry.Open(); using var output = File.Create(target); await input.CopyToAsync(output, cancellation); }
            foreach (var required in new[] { "model/model.bin", "model/config.json", "source.spm", "target.spm" }) if (!File.Exists(Path.Combine(stage, required))) throw new InvalidDataException("В модели отсутствует " + required);
            await File.WriteAllTextAsync(Path.Combine(stage, ".verified"), m.GetProperty("sha256").GetString(), cancellation);
            if (Directory.Exists(ModelFolder)) Directory.Delete(ModelFolder, true); Directory.Move(stage, ModelFolder);
        } finally { if (Directory.Exists(stage)) Directory.Delete(stage, true); modelGate.Release(); }
    }
    public async Task DeleteModel()
    {
        if (Downloading) throw new InvalidOperationException("Сначала отмените загрузку");
        await modelGate.WaitAsync(); try { if (Directory.Exists(ModelFolder)) Directory.Delete(ModelFolder, true); } finally { modelGate.Release(); }
    }
    public async Task<string> Offline(string text)
    {
        CheckText(text); await modelGate.WaitAsync();
        try {
            if (!Ready) throw new InvalidOperationException("Сначала загрузите офлайн-переводчик в настройках");
            var exe = Path.Combine(applicationFolder, "Translator", "Runtime", "python.exe");
            if (!File.Exists(exe)) throw new InvalidOperationException("Не найден движок перевода. Переустановите полную Windows-сборку.");
            var start = new ProcessStartInfo(exe) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, StandardInputEncoding = new UTF8Encoding(false), StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8 };
            start.ArgumentList.Add("-I"); start.ArgumentList.Add(Path.Combine(applicationFolder, "Translator", "runner.py")); start.ArgumentList.Add(ModelFolder); start.Environment["PYTHONIOENCODING"] = "utf-8";
            using var process = Process.Start(start) ?? throw new InvalidOperationException("Не удалось запустить переводчик");
            try {
                var errors = process.StandardError.ReadToEndAsync();
                await process.StandardInput.WriteLineAsync(JsonSerializer.Serialize(new { text })); process.StandardInput.Close();
                var output = await process.StandardOutput.ReadLineAsync().WaitAsync(TimeSpan.FromSeconds(90));
                await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
                if (output == null) throw new InvalidOperationException("Переводчик не ответил. Проверьте полную установку и совместимость процессора.");
                using var doc = JsonDocument.Parse(output); if (doc.RootElement.TryGetProperty("error", out var error)) throw new InvalidOperationException(error.GetString());
                return doc.RootElement.GetProperty("translation").GetString() ?? throw new InvalidDataException("Пустой перевод");
            } finally { if (!process.HasExited) process.Kill(true); }
        } finally { modelGate.Release(); }
    }
    static void CheckText(string text) { if (string.IsNullOrWhiteSpace(text) || text.Length > 3000) throw new InvalidDataException("Выделите от 1 до 3000 символов"); }
    public async Task<string> Online(string text)
    {
        CheckText(text); if (Encoding.UTF8.GetByteCount(text) > 500) throw new InvalidDataException("MyMemory принимает до 500 байт за запрос. Выделите меньший фрагмент.");
        using var cancel = new CancellationTokenSource(TimeSpan.FromSeconds(25));
        var json = await http.GetStringAsync("https://api.mymemory.translated.net/get?langpair=en%7Cru&q=" + Uri.EscapeDataString(text), cancel.Token);
        using var doc = JsonDocument.Parse(json); if (doc.RootElement.GetProperty("responseStatus").GetInt32() != 200) throw new InvalidOperationException("MyMemory не выполнил перевод. Попробуйте позже.");
        return WebUtility.HtmlDecode(doc.RootElement.GetProperty("responseData").GetProperty("translatedText").GetString() ?? "");
    }
    public void Dispose() { CancelDownload(); http.Dispose(); }
}
