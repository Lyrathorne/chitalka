using System.Windows;
namespace Chitalka.Windows;
public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += (_, a) => { MessageBox.Show(a.Exception.Message, "Читалка — ошибка"); a.Handled = true; };
        new MainWindow(e.Args).Show();
    }
}
