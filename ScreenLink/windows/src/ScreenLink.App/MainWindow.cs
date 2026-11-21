using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using ScreenLink.Core.Logic;
using ScreenLink.UI;

namespace ScreenLink.App;

/// <summary>Thin window: title, icon, Mica backdrop and size; the content is <see cref="ScreenLink.UI.Shell"/>.</summary>
public sealed partial class MainWindow : Window, IDisposable
{
    public Shell? Shell { get; private set; }

    public MainWindow()
    {
        Title = Strings.AppTitle;
        try { SystemBackdrop = new MicaBackdrop(); } catch (Exception ex) { AppLog.Info("startup", "Mica unavailable: " + ex.Message); }
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1280, 820));
        var icon = Path.Combine(AppContext.BaseDirectory, "ScreenLink.ico");
        if (File.Exists(icon)) AppWindow.SetIcon(icon);
        Shell = new Shell(this);
        Content = Shell;
        Closed += (_, _) => { AppLog.Info("shutdown", "window closed"); Dispose(); };
    }

    public void Dispose() => Shell?.Dispose();
}
