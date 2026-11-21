using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Spatia.Core.Logic;
using Spatia.UI;

namespace Spatia.App;

/// <summary>Thin window: title, icon, Mica backdrop and size; the content is <see cref="Spatia.UI.Shell"/>.</summary>
public sealed partial class MainWindow : Window, IDisposable
{
    public Shell? Shell { get; private set; }

    public MainWindow()
    {
        InitializeComponent();
        Title = UiStrings.S["AppTitle"];
        try { SystemBackdrop = new MicaBackdrop(); } catch (Exception ex) { AppLog.Info("startup", "Mica unavailable: " + ex.Message); }
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1100, 820));
        var icon = Path.Combine(AppContext.BaseDirectory, "Spatia.ico");
        if (File.Exists(icon)) AppWindow.SetIcon(icon);

        Shell = new Shell(this);
        Root.Children.Add(Shell);
        Closed += (_, _) => { AppLog.Info("shutdown", "window closed"); Dispose(); };
    }

    public void Dispose() => Shell?.Dispose();
}
