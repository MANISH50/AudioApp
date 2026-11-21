using Microsoft.UI.Xaml;
using Spatia.Core.Logic;
using Spatia.UI;

namespace Spatia.App;

/// <summary>
/// Standard WinUI application (XAML-generated entry point and type metadata). Command-line Equalizer APO
/// connect/disconnect (the elevated helper) is handled first and exits without UI. Any failure before the window is up is
/// logged and shown in a native dialog, then the process exits: Spatia never keeps running invisibly.
/// </summary>
public partial class App : Application, IDisposable
{
    private MainWindow? _window;

    public App()
    {
        AppLog.Initialize();
        // Elevated Equalizer APO helper (Spatia.exe --apo-connect <SID> / --apo-disconnect): do the file work and exit
        // before any window or audio service starts.
        var args = System.Environment.GetCommandLineArgs();
        if (Spatia.Core.Apo.ApoCommandLine.TryRun(args.Length > 1 ? args[1..] : []) is { } exitCode)
        {
            System.Environment.Exit(exitCode);
        }
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex) Fatal.ReportAndExit("unhandled", ex);
        };
        TaskScheduler.UnobservedTaskException += (_, e) => { AppLog.Error("background task", e.Exception); e.SetObserved(); };
        if (AppLog.DiagnosticsEnabled) AppDomain.CurrentDomain.FirstChanceException += (_, e) => AppLog.FirstChance(e.Exception);
        UnhandledException += OnUnhandled;

        try
        {
            InitializeComponent();
            UiStrings.Load(System.Globalization.CultureInfo.CurrentUICulture);
            if (AppLog.DiagnosticsEnabled)
            {
                DebugSettings.BindingFailed += (_, e) => AppLog.Info("xaml binding", e.Message);
                DebugSettings.XamlResourceReferenceFailed += (_, e) => AppLog.Info("xaml resource", e.Message);
            }
            AppLog.Info("startup", $"strings loaded ({UiStrings.S.Keys.Count})");
        }
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
        GC.SuppressFinalize(this);
    }
}
