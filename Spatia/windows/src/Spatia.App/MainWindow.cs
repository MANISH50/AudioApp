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
        // Diagnostics-only switches (SPATIA_DIAGNOSTICS=1) let CI isolate window-level startup crashes.
        static bool Diag(string name) => AppLog.DiagnosticsEnabled && System.Environment.GetEnvironmentVariable(name) == "1";
        if (!Diag("SPATIA_DIAG_NO_MICA"))
        {
            try { SystemBackdrop = new MicaBackdrop(); } catch (Exception ex) { AppLog.Info("startup", "Mica unavailable: " + ex.Message); }
        }
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1100, 820));
        var icon = Path.Combine(AppContext.BaseDirectory, "Spatia.ico");
        if (File.Exists(icon) && !Diag("SPATIA_DIAG_NO_ICON")) AppWindow.SetIcon(icon);

        if (Diag("SPATIA_DIAG_MINIMAL"))
        {
            AppLog.Info("startup", "diagnostics: minimal content");
            Content = new Microsoft.UI.Xaml.Controls.TextBlock { Text = "Spatia diagnostics: minimal window" };
        }
        else
        {
            AppLog.Info("startup", "building main view");
            Shell = new Shell(this);
            Content = Shell;
        }
        if (Content is FrameworkElement fe) fe.Loaded += (_, _) => AppLog.Info("startup", "window content loaded");
        Closed += (_, _) => { AppLog.Info("shutdown", "window closed"); Dispose(); };
    }

    public void Dispose() => Shell?.Dispose();
}
