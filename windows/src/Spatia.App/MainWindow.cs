using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Spatia.Core.Logic;
using Spatia.UI;

namespace Spatia.App;

/// <summary>
/// Thin window: title, icon, Mica backdrop and size; the content is <see cref="Spatia.UI.Shell"/>. Created in code
/// (the window holds a single control, so it needs no XAML of its own).
/// </summary>
public sealed partial class MainWindow : Window, IDisposable
{
    public Shell? Shell { get; private set; }

    public MainWindow()
    {
        Title = UiStrings.S["AppTitle"];
        try { SystemBackdrop = new MicaBackdrop(); } catch (Exception ex) { AppLog.Info("startup", "Mica unavailable: " + ex.Message); }
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1100, 820));
        var icon = Path.Combine(AppContext.BaseDirectory, "Spatia.ico");
        if (File.Exists(icon)) AppWindow.SetIcon(icon);

        AppLog.Info("startup", "building main view");
        Shell = new Shell(this);
        Content = Shell;
        Closed += (_, _) => { AppLog.Info("shutdown", "window closed"); Dispose(); };
    }

    public void Dispose() => Shell?.Dispose();
}
