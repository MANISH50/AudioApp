using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using ScreenLink.Core.Logic;
using ScreenLink.Core.Net;
using ScreenLink.Core.Protocol;
using ScreenLink.UI.Ui;
using ScreenLink.UI.Video;
using Windows.System;

namespace ScreenLink.UI;

/// <summary>
/// The whole window: a connection panel on the left (address, pairing, typing switch, paired phones, firewall help)
/// and the phone's screen on the right. F11 or the full-screen button shows only the phone screen; Esc returns.
/// </summary>
public sealed partial class Shell : UserControl, IDisposable
{
    private readonly Window _window;
    private readonly AppController _app;
    private readonly H264Player? _player;
    private readonly ScrollViewer _panel;
    private readonly ColumnDefinition _panelColumn = new() { Width = new GridLength(380) };
    private readonly TextBlock _status = Kit.Body("");
    private readonly TextBlock _overlay = new()
    {
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        TextWrapping = TextWrapping.WrapWholeWords, TextAlignment = TextAlignment.Center, MaxWidth = 420,
        Foreground = new SolidColorBrush(Colors.White), Opacity = 0.85,
    };
    private readonly Button _disconnect;
    private readonly StackPanel _pairedList = new() { Spacing = 4 };
    private readonly InfoBar _info = new() { IsClosable = true };
    private ContentDialog? _pairingDialog;
    private PairingRequest? _pairing;
    private string? _phone;
    private bool _casting;
    private bool _fullScreen;
    private bool _controlReady;
    private bool _videoFocused;
    private bool _dragging;
    private int _videoW, _videoH;
    private char _highSurrogate;
    private readonly System.Diagnostics.Stopwatch _moveClock = System.Diagnostics.Stopwatch.StartNew();
    private readonly Grid _input = new() { Background = new SolidColorBrush(Colors.Transparent) };
    private readonly StackPanel _toolbar = new() { Orientation = Orientation.Horizontal, Spacing = 8, Margin = new Thickness(12), HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top };
    private readonly TextBlock _controlHint = new()
    {
        HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(16),
        TextWrapping = TextWrapping.WrapWholeWords, TextAlignment = TextAlignment.Center, MaxWidth = 520,
        Foreground = new SolidColorBrush(Colors.White), IsHitTestVisible = false,
    };

    public Shell(Window window)
    {
        _window = window;
        _app = new AppController(DispatcherQueue);

        // Left: connection panel.
        var typing = new ToggleSwitch { Header = Strings.AllowTyping, IsOn = _app.Settings.AllowTyping };
        AutomationProperties.SetName(typing, Strings.AllowTyping);
        typing.Toggled += (_, _) => { _app.Settings.AllowTyping = typing.IsOn; _app.Settings.Save(); };
        _disconnect = Kit.Button(Strings.Disconnect, () => _app.Server?.DisconnectPhone());

        var addresses = AppController.Addresses();
        var port = _app.Server?.Port ?? Wire.TcpPort;
        var addressText = addresses.Count == 0 ? Strings.NoNetwork
            : string.Join(Environment.NewLine, addresses.Select(a => port == Wire.TcpPort ? a : $"{a}:{port}"));
        var address = new TextBlock { Text = addressText, IsTextSelectionEnabled = true }.Style("BodyStrongTextBlockStyle");
        AutomationProperties.SetName(address, Strings.AddressLabel);

        var panelContent = new StackPanel { Spacing = 12, Padding = new Thickness(20, 16, 20, 24) };
        panelContent.Children.Add(Kit.Title("ScreenLink"));
        panelContent.Children.Add(_info);
        panelContent.Children.Add(Kit.Section(Strings.StatusHeading, _status, _disconnect));
        panelContent.Children.Add(Kit.Section(Strings.ConnectHeading,
            Kit.Body(Strings.ConnectHelp), address,
            Kit.Caption(Strings.F(Strings.LaptopIdentity, _app.LaptopName, _app.FingerprintShort))));
        panelContent.Children.Add(Kit.Section(Strings.KeyboardHeading, typing, Kit.Caption(Strings.KeyboardHelp)));
        panelContent.Children.Add(Kit.Section(Strings.PairedHeading, _pairedList));
        panelContent.Children.Add(Kit.Section(Strings.TroubleHeading, Kit.Body(Strings.FirewallHelp), Kit.Button(Strings.FirewallButton, AllowFirewall)));
        panelContent.Children.Add(Kit.Caption(Strings.F(Strings.LogLocation, AppLog.FilePath)));
        _panel = new ScrollViewer { Content = panelContent };

        // Right: the phone's screen.
        var video = new MediaPlayerElement { Stretch = Stretch.Uniform, AreTransportControlsEnabled = false, HorizontalAlignment = HorizontalAlignment.Stretch, VerticalAlignment = VerticalAlignment.Stretch };
        AutomationProperties.SetName(video, Strings.PhoneScreen);
        var fullScreen = Kit.Button(Strings.FullScreen, ToggleFullScreen);
        fullScreen.HorizontalAlignment = HorizontalAlignment.Right;
        fullScreen.VerticalAlignment = VerticalAlignment.Top;
        fullScreen.Margin = new Thickness(12);
        var viewer = new Grid { Background = new SolidColorBrush(Colors.Black) };
        _overlay.IsHitTestVisible = false;
        AutomationProperties.SetName(_input, Strings.ControlInput);
        _toolbar.Children.Add(Kit.Button(Strings.Back, () => SendGlobal(Messages.GlobalBack)));
        _toolbar.Children.Add(Kit.Button(Strings.Home, () => SendGlobal(Messages.GlobalHome)));
        _toolbar.Children.Add(Kit.Button(Strings.Recents, () => SendGlobal(Messages.GlobalRecents)));
        viewer.Children.Add(video);
        viewer.Children.Add(_input);
        viewer.Children.Add(_overlay);
        viewer.Children.Add(_controlHint);
        viewer.Children.Add(_toolbar);
        viewer.Children.Add(fullScreen);
        HookControlInput();

        var root = new Grid();
        root.ColumnDefinitions.Add(_panelColumn);
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        Grid.SetColumn(viewer, 1);
        root.Children.Add(_panel);
        root.Children.Add(viewer);
        Content = root;

        var f11 = new KeyboardAccelerator { Key = VirtualKey.F11 };
        f11.Invoked += (_, e) => { ToggleFullScreen(); e.Handled = true; };
        var esc = new KeyboardAccelerator { Key = VirtualKey.Escape };
        esc.Invoked += (_, e) => { if (_fullScreen) { ToggleFullScreen(); e.Handled = true; } };
        KeyboardAccelerators.Add(f11);
        KeyboardAccelerators.Add(esc);

        if (_app.Server is { } server)
        {
            _player = new H264Player(video, DispatcherQueue, server.RequestKeyframe);
            _player.VideoStarted += (w, h) => { _videoW = w; _videoH = h; Refresh(); };
            server.VideoConfigReceived += _player.OnConfig;
            server.VideoFrameReceived += _player.OnFrame;
        }
        _app.PairingRequested += ShowPairing;
        _app.PairingEnded += r => { if (r == _pairing) { _pairingDialog?.Hide(); _pairing = null; } };
        _app.PhoneConnected += n => { _phone = n; Refresh(); };
        _app.PhoneDisconnected += () => { _phone = null; _casting = false; _controlReady = false; _player?.Stop(); Refresh(); };
        _app.CastStateChanged += c => { _casting = c; if (!c) { _player?.Stop(); _videoW = _videoH = 0; } Refresh(); };
        _app.ControlStateChanged += c => { _controlReady = c; Refresh(); };
        _app.Paired.Changed += () => DispatcherQueue.TryEnqueue(RefreshPaired);

        if (_app.StartError is { } err) ShowInfo(InfoBarSeverity.Error, Strings.StartFailed + " " + err);
        Refresh();
        RefreshPaired();
        AppLog.Info("startup", "user interface ready");
    }

    private void Refresh()
    {
        _status.Text = _app.Server is null ? Strings.StatusUnavailable
            : _phone is null ? Strings.StatusWaiting
            : Strings.F(_casting ? Strings.StatusCasting : Strings.StatusConnected, _phone);
        _disconnect.IsEnabled = _phone is not null;
        _overlay.Text = _casting ? "" : _phone is null ? Strings.OverlayWaiting : Strings.OverlayNotCasting;
        _overlay.Visibility = _casting ? Visibility.Collapsed : Visibility.Visible;
        var canControl = _casting && _controlReady;
        _toolbar.Visibility = canControl ? Visibility.Visible : Visibility.Collapsed;
        _controlHint.Text = Strings.ControlOffHint;
        _controlHint.Visibility = _casting && !_controlReady ? Visibility.Visible : Visibility.Collapsed;
    }

    private bool ControlActive => _casting && _controlReady && _videoW > 0 && _app.Server is not null;

    private void SendGlobal(byte code)
    {
        if (ControlActive) _app.Server!.SendControl(Wire.ControlGlobal, [code]);
    }

    private (ushort X, ushort Y)? MapPoint(Windows.Foundation.Point p, bool clamp) =>
        Core.Control.PointerMapper.Map(p.X, p.Y, _input.ActualWidth, _input.ActualHeight, _videoW, _videoH, clamp);

    private void SendPointer(byte action, (ushort X, ushort Y) at) =>
        _app.Server?.SendControl(Wire.ControlPointer, Messages.PointerEvent(action, at.X, at.Y));

    /// <summary>
    /// Mouse and keyboard on the phone picture → the phone (when the phone allows control): click = tap, drag = swipe,
    /// wheel = scroll, right-click = Back; typing goes into the phone's focused text field once the picture was clicked.
    /// </summary>
    private void HookControlInput()
    {
        IsTabStop = true;
        _input.PointerPressed += (_, e) =>
        {
            _videoFocused = true;
            Focus(FocusState.Pointer);
            if (!ControlActive) return;
            var pt = e.GetCurrentPoint(_input);
            if (pt.Properties.IsRightButtonPressed) { SendGlobal(Messages.GlobalBack); e.Handled = true; return; }
            if (!pt.Properties.IsLeftButtonPressed || MapPoint(pt.Position, false) is not { } at) return;
            _dragging = _input.CapturePointer(e.Pointer);
            SendPointer(Messages.PointerDown, at);
            _moveClock.Restart();
            e.Handled = true;
        };
        _input.PointerMoved += (_, e) =>
        {
            if (!_dragging || _moveClock.ElapsedMilliseconds < 16) return; // ~60 updates per second is plenty
            _moveClock.Restart();
            if (MapPoint(e.GetCurrentPoint(_input).Position, true) is { } at) SendPointer(Messages.PointerMove, at);
        };
        _input.PointerReleased += (_, e) =>
        {
            if (!_dragging) return;
            _dragging = false;
            if (MapPoint(e.GetCurrentPoint(_input).Position, true) is { } at) SendPointer(Messages.PointerUp, at);
            _input.ReleasePointerCapture(e.Pointer);
            e.Handled = true;
        };
        _input.PointerCaptureLost += (_, _) =>
        {
            if (!_dragging) return;
            _dragging = false;
            SendPointer(Messages.PointerCancel, (0, 0));
        };
        _input.PointerWheelChanged += (_, e) =>
        {
            if (!ControlActive) return;
            var pt = e.GetCurrentPoint(_input);
            if (MapPoint(pt.Position, false) is not { } at) return;
            var delta = (short)Math.Clamp(pt.Properties.MouseWheelDelta, short.MinValue, short.MaxValue);
            _app.Server!.SendControl(Wire.ControlScroll, Messages.Scroll(at.X, at.Y, delta));
            e.Handled = true;
        };
        _panel.AddHandler(PointerPressedEvent, new PointerEventHandler((_, _) => _videoFocused = false), true);

        CharacterReceived += (_, e) =>
        {
            if (!_videoFocused || !ControlActive) return;
            var c = e.Character;
            if (char.IsHighSurrogate(c)) { _highSurrogate = c; return; }
            string text;
            if (char.IsLowSurrogate(c))
            {
                if (_highSurrogate == '\0') return;
                text = new string([_highSurrogate, c]);
                _highSurrogate = '\0';
            }
            else
            {
                _highSurrogate = '\0';
                if (char.IsControl(c)) return; // Backspace/Enter come through KeyDown
                text = c.ToString();
            }
            _app.Server!.SendControl(Wire.ControlText, Messages.Text(text));
            e.Handled = true;
        };
        KeyDown += (_, e) =>
        {
            if (!_videoFocused || !ControlActive) return;
            byte? code = e.Key switch
            {
                VirtualKey.Back => Messages.KeyBackspace,
                VirtualKey.Enter => Messages.KeyEnter,
                _ => null,
            };
            if (code is { } k) { _app.Server!.SendControl(Wire.ControlKey, [k]); e.Handled = true; }
        };
    }

    private void RefreshPaired()
    {
        _pairedList.Children.Clear();
        var all = _app.Paired.All;
        if (all.Count == 0) { _pairedList.Children.Add(Kit.Caption(Strings.NoPaired)); return; }
        foreach (var p in all)
        {
            var remove = Kit.Button(Strings.Remove, () =>
            {
                _app.Paired.Remove(p.Fingerprint);
                if (_phone == p.Name) _app.Server?.DisconnectPhone();
            });
            AutomationProperties.SetName(remove, $"{Strings.Remove} {p.Name}");
            var row = new Grid();
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var name = Kit.Body(p.Name);
            name.VerticalAlignment = VerticalAlignment.Center;
            Grid.SetColumn(remove, 1);
            row.Children.Add(name);
            row.Children.Add(remove);
            _pairedList.Children.Add(row);
        }
    }

    private async void ShowPairing(PairingRequest r)
    {
        _pairing = r;
        var code = new TextBlock
        {
            Text = r.Code[..3] + " " + r.Code[3..],
            FontSize = 40,
            FontWeight = Microsoft.UI.Text.FontWeights.Bold,
            FontFamily = new FontFamily("Consolas"),
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        AutomationProperties.SetName(code, string.Join(' ', r.Code.ToCharArray()));
        var content = new StackPanel { Spacing = 12 };
        content.Children.Add(Kit.Body(Strings.PairBody));
        content.Children.Add(code);
        content.Children.Add(Kit.Caption(Strings.PairWarning));
        _pairingDialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = Strings.F(Strings.PairTitle, r.PhoneName),
            Content = content,
            PrimaryButtonText = Strings.Allow,
            CloseButtonText = Strings.Deny,
            DefaultButton = ContentDialogButton.Close,
        };
        try
        {
            // Bring the window forward so the request isn't missed.
            _window.Activate();
            var result = await _pairingDialog.ShowAsync();
            r.Answer(result == ContentDialogResult.Primary);
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException)
        {
            AppLog.Error("pairing dialog", e);
            r.Answer(false);
        }
        finally
        {
            if (_pairing == r) _pairing = null;
            _pairingDialog = null;
        }
    }

    private void ToggleFullScreen()
    {
        _fullScreen = !_fullScreen;
        _panel.Visibility = _fullScreen ? Visibility.Collapsed : Visibility.Visible;
        _panelColumn.Width = _fullScreen ? new GridLength(0) : new GridLength(380);
        try { _window.AppWindow.SetPresenter(_fullScreen ? AppWindowPresenterKind.FullScreen : AppWindowPresenterKind.Default); }
        catch (System.Runtime.InteropServices.COMException e) { AppLog.Error("fullscreen", e); }
    }

    private void AllowFirewall()
    {
        _ = Task.Run(() =>
        {
            var code = Firewall.RunElevated(Firewall.AddArg);
            DispatcherQueue.TryEnqueue(() =>
            {
                if (code == 0) ShowInfo(InfoBarSeverity.Success, Strings.FirewallDone);
                else if (code == 2) ShowInfo(InfoBarSeverity.Informational, Strings.FirewallCancelled);
                else ShowInfo(InfoBarSeverity.Error, Strings.FirewallFailed);
            });
        });
    }

    public void ShowError(string message) => ShowInfo(InfoBarSeverity.Error, message);

    private void ShowInfo(InfoBarSeverity severity, string message)
    {
        _info.Severity = severity;
        _info.Message = message;
        _info.IsOpen = true;
    }

    public void Dispose()
    {
        _player?.Dispose();
        _app.Dispose();
    }
}
