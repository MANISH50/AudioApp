using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Spatia.UI.Ui;
using Spatia.Core.Logic;
using static Spatia.UI.Ui.Kit;

namespace Spatia.UI.Pages;

public sealed partial class PlayerPage : UserControl
{
    private readonly AppController _app;
    private readonly TextBlock _title = new TextBlock().Style("SubtitleTextBlockStyle");
    private readonly Button _playPause;
    private readonly Slider _position = new() { Minimum = 0, Maximum = 1, StepFrequency = 0.001 };
    private readonly TextBlock _time = Caption("");
    private readonly TextBlock _guide = Body("");
    private readonly InfoBar _error = new() { Severity = InfoBarSeverity.Warning, IsClosable = true };
    private readonly ToggleSwitch _bypass;
    private readonly ProgressBar _peak = new() { Minimum = -60, Maximum = 0, Height = 8 };
    private readonly ProgressBar _rms = new() { Minimum = -60, Maximum = 0, Height = 8 };
    private readonly TextBlock _peakText = Caption("");
    private readonly TextBlock _rmsText = Caption("");
    private readonly Canvas _spectrum = new() { Height = 96 };
    private readonly Rectangle[] _bars = new Rectangle[24];
    private readonly RadioButtons _sleep;
    private readonly TextBlock _sleepText = Caption("");
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _meterTimer;
    private static readonly int[] SleepOptions = [0, 15, 30, 60];

    public PlayerPage(AppController app, Window window)
    {
        _app = app;
        var s = UiStrings.S;
        _playPause = Kit.Button(s["Play"], app.Player.PlayPause);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_position, s["PlaybackPosition"]);
        _position.ValueChanged += (_, e) =>
        {
            if (!Updating.Active && app.Player.Duration > TimeSpan.Zero)
                app.Player.Seek(TimeSpan.FromSeconds(e.NewValue * app.Player.Duration.TotalSeconds));
        };
        _error.Closed += (_, _) => app.ClearError();
        _bypass = Toggle(s["BypassTitle"], v => app.Edit(x => x with { Bypass = v }), s["BypassHelp"]);
        _sleep = Choices(s["SleepTitle"], [s["SleepOff"], s.Format("SleepMinutes", 15), s.Format("SleepMinutes", 30), s.Format("SleepMinutes", 60)],
            i => app.SetSleepTimer(SleepOptions[i]), columns: 4);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_spectrum, s["MeterSpectrum"]);
        var accent = Application.Current.Resources.TryGetValue("AccentFillColorDefaultBrush", out var b) ? (Brush)b : new SolidColorBrush(Microsoft.UI.Colors.Teal);
        for (var i = 0; i < _bars.Length; i++) { _bars[i] = new Rectangle { Fill = accent }; _spectrum.Children.Add(_bars[i]); }

        Content = Page(
            Title(s["NavPlayer"]),
            _error,
            Section(s["NowPlaying"],
                _title,
                Row(
                    Kit.Button(s["OpenFile"], async () =>
                    {
                        var path = await Files.PickAsync(window, FormatSupport.PickerExtensions);
                        if (path is null) return;
                        var v = app.OpenFile(path);
                        if (v is not (FormatVerdict.Supported or FormatVerdict.OpusDecoder)) ShowError(s["Format_" + v]);
                    }, accent: true),
                    Kit.Button(s["PlaySample"], app.PlaySample),
                    _playPause),
                _position, _time, _guide),
            Section(s["CompareTitle"], _bypass),
            Section(s["MetersTitle"], _peakText, _peak, _rmsText, _rms, _spectrum),
            Section(s["SleepTitle"], _sleep, _sleepText));

        _meterTimer = DispatcherQueue.CreateTimer();
        _meterTimer.Interval = TimeSpan.FromMilliseconds(100);
        _meterTimer.Tick += (_, _) => UpdateMeters();
        Loaded += (_, _) => { app.Changed += Refresh; _meterTimer.Start(); Refresh(); };
        Unloaded += (_, _) => { app.Changed -= Refresh; _meterTimer.Stop(); };
    }

    private void ShowError(string message)
    {
        _error.Title = UiStrings.S["CannotPlay"];
        _error.Message = message;
        _error.IsOpen = true;
    }

    private void Refresh()
    {
        var s = UiStrings.S;
        var p = _app.Player;
        _title.Text = p.Title ?? s["NothingLoaded"];
        _playPause.Content = s[p.IsPlaying ? "Pause" : "Play"];
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_playPause, (string)_playPause.Content);
        _playPause.IsEnabled = p.Title is not null;
        var dur = p.Duration.TotalSeconds;
        _position.IsEnabled = dur > 0;
        if (dur > 0) _position.Set(p.Position.TotalSeconds / dur);
        _time.Text = dur > 0 ? $"{Labels.Time(p.Position)} / {Labels.Time(p.Duration)}" : "";
        if (p.IsSample)
        {
            var sec = p.Position.TotalSeconds;
            var key = sec < 8 ? "SampleGroove" : sec < 14 ? "SampleVoice" : sec < 20 ? "SampleHits" : "SampleSweep";
            _guide.Text = s.Format("SampleNow", s[key]) + "\n" + s["SampleGuide"];
        }
        else _guide.Text = p.ExclusiveMode ? s["ExclusiveActiveNote"] : s["PlayerFormats"];
        if (_app.LastError is { } err) ShowError(s["PlayerError_" + err]);
        _bypass.Set(_app.Settings.Bypass);
        _sleep.Set(Array.IndexOf(SleepOptions, _app.SleepMinutes));
        _sleepText.Text = _app.SleepRemainingS > 0 ? s.Format("SleepRemaining", Labels.Time(TimeSpan.FromSeconds(_app.SleepRemainingS))) : "";
    }

    private void UpdateMeters()
    {
        var chain = _app.Player.Chain;
        if (chain is null || !_app.Player.IsPlaying) return;
        var m = chain.Meters();
        _peak.Value = Math.Clamp(m.PeakDb, -60, 0);
        _rms.Value = Math.Clamp(m.RmsDb, -60, 0);
        _peakText.Text = UiStrings.S.Format("MeterPeak", m.PeakDb <= -120 ? "−∞" : m.PeakDb.ToString("0.0", System.Globalization.CultureInfo.CurrentCulture));
        _rmsText.Text = UiStrings.S.Format("MeterRms", m.RmsDb <= -120 ? "−∞" : m.RmsDb.ToString("0.0", System.Globalization.CultureInfo.CurrentCulture));
        var bands = chain.Spectrum(_bars.Length);
        var w = Math.Max(1, _spectrum.ActualWidth) / _bars.Length;
        for (var i = 0; i < _bars.Length; i++)
        {
            var h = Math.Clamp((bands[i] + 80) / 80, 0, 1) * _spectrum.Height;
            _bars[i].Width = Math.Max(1, w - 2);
            _bars[i].Height = h;
            Canvas.SetLeft(_bars[i], i * w);
            Canvas.SetTop(_bars[i], _spectrum.Height - h);
        }
    }
}
