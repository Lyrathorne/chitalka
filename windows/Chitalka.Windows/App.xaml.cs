using System.Windows;
using System.Security.Cryptography;
using System.Text;
using System.IO;
namespace Chitalka.Windows;
public partial class App : Application
{
    Mutex? profileMutex;
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var index = Array.IndexOf(e.Args, "--profile");
        var root = index >= 0 && index + 1 < e.Args.Length ? e.Args[index+1] : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Chitalka");
        var key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(root).ToUpperInvariant())));
        profileMutex = new Mutex(false, "Local\\Chitalka-" + key);
        bool acquired; try { acquired = profileMutex.WaitOne(0); } catch (AbandonedMutexException) { acquired = true; }
        if (!acquired) { MessageBox.Show("Читалка уже открыта. Добавьте EPUB через кнопку или перетащите его в открытое окно.", "Читалка"); Shutdown(); return; }
        DispatcherUnhandledException += (_, a) => { MessageBox.Show(a.Exception.Message, "Читалка — ошибка"); a.Handled = true; };
        new MainWindow(e.Args).Show();
    }
    protected override void OnExit(ExitEventArgs e) { try { profileMutex?.ReleaseMutex(); } catch (ApplicationException) { } profileMutex?.Dispose(); base.OnExit(e); }
}
