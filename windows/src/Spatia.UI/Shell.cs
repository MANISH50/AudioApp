using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Spatia.Core.Logic;
using Spatia.Core.Native;
using Spatia.UI.Pages;

namespace Spatia.UI;

/// <summary>
/// The app's content: navigation between Player, Sound, Output, Other apps and Status. Hosted by the exe's
/// MainWindow. Every startup stage is logged, and failures are shown instead of leaving an empty window.
/// </summary>
public sealed partial class Shell : UserControl, IDisposable
{
    private readonly Window _window;
    private readonly AppController? _app;
    private readonly NavigationView _nav = new() { IsSettingsVisible = false, IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed, PaneDisplayMode = NavigationViewPaneDisplayMode.Left };
    private readonly Dictionary<string, Func<FrameworkElement>> _pages = [];
    private readonly InfoBar _error = new() { Severity = InfoBarSeverity.Error, IsClosable = true };
    private bool _warningOpen;

    public Shell(Window window)
    {
        _window = window;
        var s = UiStrings.S;
        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Grid.SetRow(_error, 0);
        Grid.SetRow(_nav, 1);
        root.Children.Add(_error);
        root.Children.Add(_nav);
        Content = root;

        AppLog.Info("startup", "checking audio engine");
        if (!EngineAvailable(out var reason))
        {
            AppLog.Error("startup", "audio engine unavailable: " + reason);
            _nav.Content = Ui.Kit.Page(Ui.Kit.Title(s["EngineMissingTitle"]), Ui.Kit.Body(reason));
            return;
        }

        AppLog.Info("startup", "starting audio services");
        _app = new AppController(DispatcherQueue);
        AppLog.Info("startup", $"audio services ready: {_app.Devices.Outputs.Count} output device(s)");

        AddPage("player", s["NavPlayer"], Symbol.Play, () => new PlayerPage(_app, _window));
        AddPage("sound", s["NavSound"], Symbol.Audio, () => new SoundPage(_app, _window));
        AddPage("output", s["NavOutput"], Symbol.Volume, () => new OutputPage(_app));
        AddPage("other", s["NavOtherApps"], Symbol.AllApps, () => new OtherAppsPage());
        AddPage("status", s["NavStatus"], Symbol.Help, () => new StatusPage(_app));
        _nav.SelectionChanged += (_, e) =>
        {
            if (e.SelectedItemContainer?.Tag is not string tag || !_pages.TryGetValue(tag, out var make)) return;
            try { _nav.Content = BuildPage(tag); }
            catch (Exception ex)
            {
                AppLog.Error("page " + tag, ex);
                ShowError(ex.Message);
            }
        };
        // Show the first page right away, so the window is never empty. Selecting a NavigationView item only works
        // once the control is in the visual tree (doing it here threw 0x80070490 "Element not found"), so the
        // selection highlight is applied on Loaded.
        var start = AppLog.DiagnosticsEnabled && System.Environment.GetEnvironmentVariable("SPATIA_START_PAGE") is { } p && _pages.ContainsKey(p) ? p : "player";
        _startTag = start;
        _nav.Content = BuildPage(start);
        Loaded += SelectFirstPageOnce;
        LayoutUpdated += LogFirstLayout;
        _app.VolumeWarning += ShowVolumeWarning;
        AppLog.Info("startup", "user interface ready");
    }

    private string _startTag = "player";
    private bool _firstLayoutLogged;

    private void LogFirstLayout(object? sender, object e)
    {
        if (_firstLayoutLogged) return;
        _firstLayoutLogged = true;
        LayoutUpdated -= LogFirstLayout;
        AppLog.Info("startup", "first layout pass completed");
    }

    /// <summary>Creates a page, logging each step so a crash during layout can be traced to the page.</summary>
    private FrameworkElement BuildPage(string tag)
    {
        AppLog.Info("page " + tag, "constructing");
        var page = _pages[tag]();
        page.Loaded += (_, _) => AppLog.Info("page " + tag, "loaded");
        AppLog.Info("page " + tag, "constructed");
        return page;
    }

    private void SelectFirstPageOnce(object sender, RoutedEventArgs e)
    {
        Loaded -= SelectFirstPageOnce;
        AppLog.Info("startup", "shell loaded");
        try
        {
            var item = _nav.MenuItems.OfType<NavigationViewItem>().First(i => (string)i.Tag == _startTag);
            _nav.SelectedItem = item;
        }
        catch (Exception ex) { AppLog.Error("selecting first page", ex); }
    }

    private static bool EngineAvailable(out string reason)
    {
        try
        {
            if (Engine.IsCompatible) { reason = ""; return true; }
            reason = UiStrings.S.Format("EngineVersionMismatch", Engine.ApiVersion, Engine.ExpectedApiVersion);
        }
        catch (DllNotFoundException) { reason = UiStrings.S["EngineMissing"]; }
        catch (EntryPointNotFoundException) { reason = UiStrings.S["EngineMissing"]; }
        catch (BadImageFormatException) { reason = UiStrings.S["EngineMissing"]; }
        return false;
    }

    private void AddPage(string tag, string label, Symbol icon, Func<FrameworkElement> make)
    {
        _pages[tag] = make;
        var item = new NavigationViewItem { Content = label, Icon = new SymbolIcon(icon), Tag = tag };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(item, label);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetAutomationId(item, "nav-" + tag);
        _nav.MenuItems.Add(item);
    }

    /// <summary>Shows an error inside the window (used once the UI exists).</summary>
    public void ShowError(string message)
    {
        _error.Title = UiStrings.S["ErrorTitle"];
        _error.Message = message;
        _error.IsOpen = true;
    }

    private async void ShowVolumeWarning()
    {
        if (_warningOpen || _app is null || XamlRoot is null) return;
        _warningOpen = true;
        try
        {
            var s = UiStrings.S;
            var dialog = new ContentDialog
            {
                XamlRoot = XamlRoot,
                Title = s["VolumeWarningTitle"],
                Content = new TextBlock { Text = s[_app.Profile.Safety.IsHeadphones ? "VolumeWarningHeadphones" : "VolumeWarningSpeaker"], TextWrapping = TextWrapping.WrapWholeWords },
                PrimaryButtonText = s["VolumeReduce"],
                CloseButtonText = s["VolumeContinue"],
                DefaultButton = ContentDialogButton.Primary,
            };
            var result = await dialog.ShowAsync();
            if (result == ContentDialogResult.Primary) _app.ReduceVolumeToSafe();
            else _app.AcknowledgeVolume();
        }
        catch (Exception ex) { AppLog.Error("volume warning", ex); }
        finally { _warningOpen = false; }
    }

    public void Dispose() => _app?.Dispose();
}
