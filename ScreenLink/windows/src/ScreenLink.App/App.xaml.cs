using Microsoft.UI.Xaml;
using ScreenLink.Core.Logic;
using ScreenLink.UI;

namespace ScreenLink.App;

/// <summary>
/// Standard WinUI application. The firewall helper modes (<c>--firewall-add</c> etc., run elevated) are handled first
/// and exit without UI. Only one ScreenLink runs at a time. Any failure before the window is up is logged and shown in
/// a native dialog, then the process exits: ScreenLink never keeps running invisibly.
/// </summary>
public partial class App : Application, IDisposable
{
    private MainWindow? _window;
    private Mutex? _single;

    public App()
    {
        AppLog.Initialize();
        var args = System.Environment.GetCommandLineArgs();
        if (Firewall.TryRun(args.Length > 1 ? args[1..] : []) is { } exitCode)
        {
            System.Environment.Exit(exitCode);
        }
        _single = new Mutex(true, @"Local\ScreenLink-single-instance", out var first);
        if (!first)
        {
            AppLog.Info("startup", "already running");
            Fatal.Show("ScreenLink is already running. Look for its window or its icon on the taskbar.");
            System.Environment.Exit(0);
        }
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex) Fatal.ReportAndExit("unhandled", ex);
        };
        TaskScheduler.UnobservedTaskException += (_, e) => { AppLog.Info("background task", e.Exception.GetBaseException().GetType().Name); e.SetObserved(); };
        if (AppLog.DiagnosticsEnabled) AppDomain.CurrentDomain.FirstChanceException += (_, e) => AppLog.FirstChance(e.Exception);
        UnhandledException += OnUnhandled;

        try { InitializeComponent(); }
        catch (Exception ex) { Fatal.ReportAndExit("loading resources", ex); }
    }

    private void OnUnhandled(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        if (_window?.Shell is { } shell)
        {
            // The window is up: keep running and show the problem inside it.
            AppLog.Error("ui", e.Exception);
            e.Handled = true;
            shell.ShowError(e.Message);
        }
        else
        {
            Fatal.ReportAndExit("starting the window", e.Exception);
        }
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            AppLog.Info("startup", "creating main window");
            _window = new MainWindow();
            _window.Activate();
            AppLog.Info("startup", "main window shown");
        }
        catch (Exception ex) { Fatal.ReportAndExit("creating the main window", ex); }
    }

    public void Dispose()
    {
        _window?.Dispose();
        _single?.Dispose();
        GC.SuppressFinalize(this);
    }
}
