using System.IO;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using Chitalka.Core;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.Windows.Interop;
namespace Chitalka.Windows;
public partial class MainWindow : Window
{
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(nint window,int attribute,ref int value,int size);
    public readonly Store Store;
    public string? QaDialogPath { get; set; }
    readonly EpubLibrary library;
    readonly DictionaryService dictionary;
    readonly TranslationService translation;
    readonly string[] arguments;
    readonly string appFolder = AppContext.BaseDirectory;
    bool closingAllowed;
    readonly SemaphoreSlim importLock = new(1,1);
    const string Origin = "https://app.chitalka.local";
    public MainWindow(string[] args)
    {
        arguments = args;
        var custom = Array.IndexOf(args, "--profile");
        var root = custom >= 0 && custom + 1 < args.Length ? args[custom + 1] : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Chitalka");
        Store = new(root); library = new(Store); dictionary = new(Path.Combine(appFolder, "Dictionary"), root); translation = new(root, appFolder, args.Contains("--qa") && args.Contains("--offline") ? new DesktopQa.NoNetworkHandler() : null);
        InitializeComponent();
        var area = SystemParameters.WorkArea; MinWidth = Math.Min(800,area.Width*.95); MinHeight = Math.Min(540,area.Height*.9); Width = Math.Min(1240,area.Width*.94); Height = Math.Min(860,area.Height*.94);
        ApplyNativeTheme(); SourceInitialized += (_,_) => ApplyNativeTheme();
        Loaded += async (_, _) => await Initialize();
        Closing += async (_, e) => { if (closingAllowed) return; e.Cancel = true; try { if (Web.CoreWebView2 != null) await Web.ExecuteScriptAsync("window.chitalka?.flushPosition()"); await Task.Delay(150); } finally { closingAllowed = true; translation.Dispose(); Close(); } };
        PreviewDragOver += (_, e) => { if (e.Data.GetDataPresent(DataFormats.FileDrop)) { e.Effects = DragDropEffects.Copy; e.Handled = true; } };
        Drop += async (_, e) => { if (e.Data.GetData(DataFormats.FileDrop) is string[] files) { try { await ImportFiles(files); await Web.ExecuteScriptAsync("window.chitalka?.refresh()"); } catch (Exception ex) { MessageBox.Show(this, ex.Message, "Не удалось добавить книгу"); } } };
    }
    async Task Initialize()
    {
        try {
            var options = new CoreWebView2EnvironmentOptions();
            var env = await CoreWebView2Environment.CreateAsync(null, Path.Combine(Store.Root, "webview"), options);
            await Web.EnsureCoreWebView2Async(env);
            Web.CoreWebView2.Settings.AreDevToolsEnabled = arguments.Contains("--qa");
            Web.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            Web.CoreWebView2.Settings.AreBrowserAcceleratorKeysEnabled = false;
            Web.CoreWebView2.Settings.IsStatusBarEnabled = false;
            Web.CoreWebView2.Settings.IsZoomControlEnabled = false;
            Web.CoreWebView2.Settings.AreHostObjectsAllowed = false;
            Web.CoreWebView2.SetVirtualHostNameToFolderMapping("app.chitalka.local", Path.Combine(appFolder, "Web"), CoreWebView2HostResourceAccessKind.DenyCors);
            Directory.CreateDirectory(Path.Combine(Store.Root, "books"));
            Web.CoreWebView2.SetVirtualHostNameToFolderMapping("books.chitalka.local", Path.Combine(Store.Root, "books"), CoreWebView2HostResourceAccessKind.Allow);
            Web.CoreWebView2.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All);
            Web.CoreWebView2.WebResourceRequested += (_, e) => {
                var uri = new Uri(e.Request.Uri);
                if (uri.Scheme is "data" or "blob" or "about") return;
                if (uri.Scheme != "https" || (uri.Host != "app.chitalka.local" && uri.Host != "books.chitalka.local")) e.Response = env.CreateWebResourceResponse(new MemoryStream(), 403, "External resource blocked", "Content-Type: text/plain");
            };
            Web.CoreWebView2.NavigationStarting += (_, e) => { if (!e.Uri.StartsWith(Origin + "/", StringComparison.Ordinal)) e.Cancel = true; };
            Web.CoreWebView2.NewWindowRequested += (_, e) => e.Handled = true;
            Web.CoreWebView2.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
            Web.CoreWebView2.DownloadStarting += (_, e) => e.Cancel = true;
            Web.CoreWebView2.WebMessageReceived += OnMessage;
            Web.CoreWebView2.NavigationCompleted += async (_, e) => {
                Loading.Visibility = Visibility.Collapsed;
                if (arguments.Contains("--qa")) { try { await new DesktopQa(this, arguments).Run(); } catch (Exception ex) { await File.WriteAllTextAsync(Path.Combine(Store.Root, "qa-failure.txt"), ex.ToString()); Environment.ExitCode = 1; } finally { closingAllowed = true; Close(); } }
                else { var files = arguments.Where(x => DocumentImporter.Supported(x) && File.Exists(x)); if (files.Any()) { await ImportFiles(files); await Web.ExecuteScriptAsync("window.chitalka?.refresh()"); } }
            };
            Web.Source = new Uri(Origin + "/index.html");
        } catch (WebView2RuntimeNotFoundException) { MessageBox.Show(this, "Не установлен Microsoft Edge WebView2 Runtime. Установите Evergreen Runtime с сайта Microsoft и запустите Читалку снова.\nhttps://developer.microsoft.com/microsoft-edge/webview2/", "Нужен WebView2"); closingAllowed = true; Close(); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "Не удалось открыть Читалку"); closingAllowed = true; Close(); }
    }
    public async Task<object> ImportFiles(IEnumerable<string> files)
    {
        await importLock.WaitAsync();try {
        var count = 0;var notices=new List<string>(); foreach (var file in files) { if (!DocumentImporter.Supported(file)) throw new InvalidDataException("Поддерживаются "+DocumentImporter.Formats); Web.CoreWebView2.PostWebMessageAsJson(Json.Encode(new{type="importProgress",name=Path.GetFileName(file)}));var book=await library.Import(file);if(book.ImportNotice!=null)notices.Add(book.ImportNotice);count++; } return new { count, notices=notices.Distinct() };
        } finally { importLock.Release(); }
    }
    async void OnMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (e.Source != Origin + "/index.html" || Web.Source?.GetLeftPart(UriPartial.Authority) != Origin) return;
        string? id = null;
        try {
            if (e.WebMessageAsJson.Length > BackupCodec.MaxBytes) throw new InvalidDataException("Слишком большой запрос");
            using var doc = JsonDocument.Parse(e.WebMessageAsJson); var r = doc.RootElement; id = r.GetProperty("id").GetString();
            // Leave the WebView2 callback before opening a native modal dialog (WebView2 forbids nested-loop reentrancy).
            await System.Windows.Threading.Dispatcher.Yield(System.Windows.Threading.DispatcherPriority.Background);
            var command = r.GetProperty("command").GetString()!;
            var result = command == "importDropped" ? await ImportFiles(e.AdditionalObjects.OfType<CoreWebView2File>().Select(x => x.Path)) : await Command(command, r.GetProperty("payload"));
            Web.CoreWebView2.PostWebMessageAsJson(Json.Encode(new { id, result }));
        } catch (Exception ex) { Web.CoreWebView2.PostWebMessageAsJson(Json.Encode(new { id, error = ex is OperationCanceledException ? "Загрузка отменена. Можно повторить." : ex.Message })); }
    }
    public async Task<object?> Command(string command, JsonElement p)
    {
        string S(string key) => p.GetProperty(key).GetString() ?? "";
        switch (command) {
            case "init": {var session=Store.State.Practice;var active=session!=null && session.Index<session.Ids.Count?Store.State.Data.Words.FirstOrDefault(w=>w.Id==session.Ids[session.Index]):null;return new { state = Store.State, model = translation.Status, version = "1.4.1", dueCount=ReviewSchedule.DueWords(Store.State.Data.Words,Store.State.Data.Reviews,Clock.Now).Count,cloze=active==null?null:PracticeExercises.Cloze(active.Context,active.Original) };}
            case "practiceCheck": {var session=Store.State.Practice??throw new InvalidOperationException("Сессия завершена");if(session.Index>=session.Ids.Count)throw new InvalidOperationException("Сессия завершена");var word=Store.State.Data.Words.First(w=>w.Id==session.Ids[session.Index]);if(word.Id!=S("id"))throw new OperationCanceledException("Карточка уже сменилась");var mode=S("mode");if(mode is not ("type" or "cloze"))throw new InvalidDataException("Неизвестный режим");var input=S("answer");if(input.Length>20000)throw new InvalidDataException("Слишком длинный ответ");var reverse=p.TryGetProperty("reverse",out var r)&&r.GetBoolean();return PracticeExercises.Matches(input,mode=="cloze"||reverse?word.Original:word.Translation);}
            case "import": { var dialog = new OpenFileDialog { Title = "Добавить книги", Filter = "Книги и документы|*.epub;*.pdf;*.docx;*.fb2;*.txt;*.zip|EPUB|*.epub|PDF (текст)|*.pdf|Word DOCX|*.docx|FB2|*.fb2;*.zip|Текст TXT|*.txt", Multiselect = true }; PrefillQaDialog(dialog); return dialog.ShowDialog(this) == true ? await ImportFiles(dialog.FileNames) : null; }
            case "position": Store.Position(S("hash"), S("locator"), p.GetProperty("progress").GetDouble()); return true;
            case "settings": { var settings = Json.Decode<Preferences>(p.GetRawText()); if (settings.Theme is not ("paper" or "night") || settings.Font is < 14 or > 40 || settings.Width is < 420 or > 1200 || settings.LineHeight is < 1.2 or > 2.2 || settings.Flow is not ("paginated" or "scrolled-doc") || settings.Sort is not ("recent" or "title")) throw new InvalidDataException("Недопустимые настройки"); Store.Change(s => s.Settings = settings); ApplyNativeTheme(); return true; }
            case "deleteBook": Store.DeleteBook(S("hash")); return true;
            case "shelfCreate": {var name=S("name").Trim();if(name.Length is <1 or >60 || name.Any(char.IsControl))throw new InvalidDataException("Введите название полки до 60 символов");Store.Change(s=>{if(!s.Shelves.TryAdd(name,[]))throw new InvalidDataException("Такая полка уже есть");});return true;}
            case "shelfToggle": {var name=S("name");var hash=S("hash");if(!Store.State.Library.Any(b=>b.Hash==hash))throw new InvalidDataException("Книга не найдена");Store.Change(s=>{if(!s.Shelves.TryGetValue(name,out var books))throw new InvalidDataException("Полка не найдена");if(!books.Remove(hash))books.Add(hash);});return true;}
            case "onlineReset": Store.Change(s=>s.OnlineAllowed=false);return true;
            case "dictionary": return await dictionary.Lookup(S("text"));
            case "offline": return new { translation = await translation.Offline(S("text")), source = "OPUS-MT EN→RU · офлайн · машинный перевод" };
            case "online": {
                if (!Store.State.OnlineAllowed && MessageBox.Show(this, "Выделенный текст будет отправлен в MyMemory (Translated.net). Сервис обрабатывает текст по своим правилам и может сохранять запросы. Не отправляйте личные данные.\n\nРазрешить отправку выделенных фрагментов по кнопке «Онлайн»? Разрешение можно сбросить в помощи.", "Онлайн-перевод MyMemory", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) throw new OperationCanceledException();
                Store.Change(s=>s.OnlineAllowed=true);
                return new { translation = await translation.Online(S("text")), source = "MyMemory · онлайн · машинный перевод" };
            }
            case "saveWord": { var w = Json.Decode<Word>(p.GetRawText()); var probe = new Backup { Words = [w] }; BackupCodec.Encode(probe); return Store.AddWord(w); }
            case "editWord": { var old = Store.State.Data.Words.First(x => x.Id == S("id")); var w = old with { Translation = S("translation"), Context = S("context"), Source = S("source") }; BackupCodec.Encode(new Backup { Words = [w] }); Store.Change(s => { s.Data.Words.RemoveAll(x => x.Id == w.Id); s.Data.Words.Add(w); }); return true; }
            case "deleteWord": { var id = S("id"); Store.Change(s => { s.Data.Words.RemoveAll(x => x.Id == id); s.Data.Reviews.RemoveAll(x => x.WordId == id); }); return true; }
            case "saveMoment": { var m = Json.Decode<Moment>(p.GetRawText()); BackupCodec.Encode(new Backup { Bookmarks = [m] }); Store.SaveMoment(m); return true; }
            case "deleteMoment": Store.Change(s => s.Data.Bookmarks.RemoveAll(x => x.Id == S("id"))); return true;
            case "practiceStart": Store.BeginPractice(); return true;
            case "practiceDue": Store.BeginPractice(true); return true;
            case "practiceRate": Store.Rate(p.GetProperty("rating").GetInt32()); return true;
            case "practiceReset": Store.Change(s => s.Practice = null); return true;
            case "copy": Clipboard.SetText(S("text")); return true;
            case "export": {
                var csv = S("kind") == "csv"; var dialog = new SaveFileDialog { FileName = $"chitalka-{DateTime.Now:yyyy-MM-dd}." + (csv ? "csv" : "json"), Filter = csv ? "Таблица CSV|*.csv" : "Резервная копия JSON|*.json" };
                PrefillQaDialog(dialog);
                if (dialog.ShowDialog(this) != true) return null; await File.WriteAllTextAsync(dialog.FileName, csv ? BackupCodec.Csv(Store.State.Data) : BackupCodec.Encode(Store.State.Data), new UTF8Encoding(false));
                if (arguments.Contains("--qa")) await File.WriteAllTextAsync(Path.Combine(Store.Root,"qa-last-export-path.txt"), dialog.FileName);
                return true;
            }
            case "restore": {
                var dialog = new OpenFileDialog { Filter = "Резервная копия Читалки|*.json" }; PrefillQaDialog(dialog); if (dialog.ShowDialog(this) != true) return null;
                if (new FileInfo(dialog.FileName).Length > BackupCodec.MaxBytes) throw new InvalidDataException("Копия превышает 16 МБ");
                var text = await File.ReadAllTextAsync(dialog.FileName); var data = BackupCodec.Decode(text);
                if (MessageBox.Show(this, $"Объединить {data.Words.Count} слов и {data.Bookmarks.Count} моментов с текущими данными?\nПеред импортом будет создана защитная копия.", "Восстановление", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return null;
                return BackupCodec.Restore(Store, text);
            }
            case "safetyCopy": { var latest = Directory.Exists(Path.Combine(Store.Root, "backups")) ? new DirectoryInfo(Path.Combine(Store.Root, "backups")).GetFiles("*.json").OrderByDescending(x => x.LastWriteTimeUtc).FirstOrDefault() : null; if (latest == null) throw new InvalidOperationException("Защитных копий пока нет"); var dialog = new SaveFileDialog { FileName = latest.Name, Filter = "JSON|*.json" }; if (dialog.ShowDialog(this) == true) File.Copy(latest.FullName, dialog.FileName, true); return true; }
            case "modelDownload": await translation.Download((count, total) => Dispatcher.Invoke(() => Web.CoreWebView2.PostWebMessageAsJson(Json.Encode(new { type = "downloadProgress", count, total })))); return translation.Status;
            case "modelCancel": translation.CancelDownload(); return true;
            case "modelInstall": { var dialog = new OpenFileDialog { Filter = "Модель Читалки|*.zip" }; if (dialog.ShowDialog(this) == true) await translation.Install(dialog.FileName); return translation.Status; }
            case "modelDelete": if (MessageBox.Show(this, "Удалить офлайн-модель? Слова и заметки сохранятся.", "Удаление модели", MessageBoxButton.YesNo) == MessageBoxResult.Yes) await translation.DeleteModel(); return translation.Status;
            case "fullscreen": if (WindowStyle == WindowStyle.None) { WindowStyle = WindowStyle.SingleBorderWindow; WindowState = WindowState.Normal; } else { WindowStyle = WindowStyle.None; WindowState = WindowState.Maximized; } return true;
            default: throw new InvalidOperationException("Неизвестная команда");
        }
    }
    void PrefillQaDialog(FileDialog dialog) { if (arguments.Contains("--qa") && QaDialogPath != null) { dialog.FileName = Path.GetFullPath(QaDialogPath); dialog.InitialDirectory = Path.GetDirectoryName(dialog.FileName); } }
    void ApplyNativeTheme() { var dark = Store.State.Settings.Theme == "night"; Web.DefaultBackgroundColor = dark ? System.Drawing.Color.FromArgb(23,28,24) : System.Drawing.Color.FromArgb(246,241,231); Background = new SolidColorBrush(dark ? Color.FromRgb(23,28,24) : Color.FromRgb(246,241,231)); var handle = new WindowInteropHelper(this).Handle; if(handle!=0) { var value = dark?1:0; DwmSetWindowAttribute(handle,20,ref value,4); } }
}
