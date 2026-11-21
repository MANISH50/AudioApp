using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Spatia.App.Pages;
using Spatia.Core.Native;

namespace Spatia.App;

/// <summary>Main window: navigation between Player, Sound, Output, Other apps and Status. Mica backdrop, follows system theme.</summary>
public sealed class MainWindow : Window, IDisposable
{
    private readonly AppController? _app;
    private readonly NavigationView _nav = new() { IsSettingsVisible = false, IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed, PaneDisplayMode = NavigationViewPaneDisplayMode.Left };
    private readonly Dictionary<string, Func<FrameworkElement>> _pages = [];
    private readonly InfoBar _error = new() { Severity = InfoBarSeverity.Error, IsClosable = true };
    private bool _warningOpen;

    public MainWindow()
    {
        var s = App.S;
        Title = s["AppTitle"];
        SystemBackdrop = new MicaBackdrop();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1100, 820));

        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Grid.SetRow(_error, 0);
        Grid.SetRow(_nav, 1);
        root.Children.Add(_error);
        root.Children.Add(_nav);
        Content = root;

        if (!EngineAvailable(out var reason))
        {
            _nav.Content = Ui.Kit.Page(Ui.Kit.Title(s["EngineMissingTitle"]), Ui.Kit.Body(reason));
            return;
        }

        _app = new AppController(DispatcherQueue);
        AddPage("player", s["NavPlayer"], Symbol.Play, () => new PlayerPage(_app, this));
        AddPage("sound", s["NavSound"], Symbol.Audio, () => new SoundPage(_app, this));
        AddPage("output", s["NavOutput"], Symbol.Volume, () => new OutputPage(_app));
        AddPage("other", s["NavOtherApps"], Symbol.AllApps, () => new OtherAppsPage());
        AddPage("status", s["NavStatus"], Symbol.Help, () => new StatusPage(_app));
        _nav.SelectionChanged += (_, e) =>
        {
            if (e.SelectedItemContainer?.Tag is string tag && _pages.TryGetValue(tag, out var make)) _nav.Content = make();
        };
        _nav.SelectedItem = _nav.MenuItems[0];

        _app.VolumeWarning += ShowVolumeWarning;
        Closed += (_, _) => Dispose();
    }

    public void Dispose() => _app?.Dispose();

    private static bool EngineAvailable(out string reason)
    {
        try
        {
            if (Engine.IsCompatible) { reason = ""; return true; }
            reason = App.S.Format("EngineVersionMismatch", Engine.ApiVersion, Engine.ExpectedApiVersion);
        }
        catch (DllNotFoundException) { reason = App.S["EngineMissing"]; }
        catch (EntryPointNotFoundException) { reason = App.S["EngineMissing"]; }
        return false;
    }

    private void AddPage(string tag, string label, Symbol icon, Func<FrameworkElement> make)
    {
        _pages[tag] = make;
        var item = new NavigationViewItem { Content = label, Icon = new SymbolIcon(icon), Tag = tag };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(item, label);
        _nav.MenuItems.Add(item);
    }

    public void Navigate(string tag)
    {
        foreach (var item in _nav.MenuItems.OfType<NavigationViewItem>())
            if ((string)item.Tag == tag) _nav.SelectedItem = item;
    }

    public void ShowError(string message)
    {
        _error.Title = App.S["ErrorTitle"];
        _error.Message = message;
        _error.IsOpen = true;
    }

    private async void ShowVolumeWarning()
    {
        if (_warningOpen || _app is null || Content.XamlRoot is null) return;
        _warningOpen = true;
        var s = App.S;
        var dialog = new ContentDialog
        {
            XamlRoot = Content.XamlRoot,
            Title = s["VolumeWarningTitle"],
            Content = new TextBlock { Text = s[_app.Profile.Safety.IsHeadphones ? "VolumeWarningHeadphones" : "VolumeWarningSpeaker"], TextWrapping = TextWrapping.WrapWholeWords },
            PrimaryButtonText = s["VolumeReduce"],
            CloseButtonText = s["VolumeContinue"],
            DefaultButton = ContentDialogButton.Primary,
        };
        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary) _app.ReduceVolumeToSafe();
        else _app.AcknowledgeVolume();
        _warningOpen = false;
    }
}
