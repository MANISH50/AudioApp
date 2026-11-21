using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Spatia.UI.Ui;
using Spatia.Core.Logic;
using Spatia.Core.Models;
using static Spatia.UI.Ui.Kit;
using Env = Spatia.Core.Models.Environment;

namespace Spatia.UI.Pages;

public sealed partial class SoundPage : UserControl
{
    private readonly AppController _app;
    private SoundSettings? _shown;
    private readonly TextBlock _editing = Caption("");
    private readonly RadioButtons _modes;
    private readonly RadioButtons _envs;
    private readonly TextBlock _envDesc = Body("");
    private readonly Slider _intensity;
    private readonly Button _resetIntensity;
    private readonly ToggleSwitch _spatial, _upmix, _headTracking;
    private readonly Slider _width, _room;
    private readonly Slider[] _eq = new Slider[10];
    private readonly Slider _bass, _treble, _preamp, _voice, _crossfeed, _psychoBass, _irMix, _balance;
    private readonly ToggleSwitch _normalize, _night, _loudComp, _correction, _ir, _hearing, _mono;
    private readonly TextBlock _correctionStatus = Caption("");
    private readonly Button _hearingStart, _irRemove;
    private readonly InfoBar _note = new() { IsClosable = true };

    private static readonly Mode[] Modes = Enum.GetValues<Mode>();
    private static readonly Env[] Envs = Enum.GetValues<Env>();

    public SoundPage(AppController app, Window window)
    {
        _app = app;
        var s = UiStrings.S;
        string Pct(double v) => $"{Math.Round(v * 100)}%";
        string Db(double v) => Labels.Db((float)v);

        _modes = Choices(s["ModesTitle"], Modes.Select(m => s["Mode_" + m]), i => app.ApplyMode(Modes[i]), columns: 3);
        _envs = Choices(s["EnvTitle"], Envs.Select(e => s["Env_" + e]), i => app.Edit(x => x with { Environment = Envs[i] }), columns: 4);
        _intensity = Slider(s["EnvIntensity"], 0, 1, 0.05,
            v => Labels.Intensity((float)v, s["EnvSubtle"], s["EnvDefault"], s["EnvIntense"]),
            v => app.Edit(x => x with { EnvironmentIntensity = v }));
        _resetIntensity = Kit.Button(s["EnvReset"], () => app.Edit(x => x with { EnvironmentIntensity = 0.5f }));

        _spatial = Toggle(s["SpatialEnable"], v => app.Tweak(x => x with { SpatialEnabled = v }), s["SpatialHelp"]);
        _width = Slider(s["SpatialWidth"], 0, 2, 0.05, Pct, v => app.Tweak(x => x with { Width = v }));
        _room = Slider(s["SpatialRoom"], 0, 1, 0.05, Pct, v => app.Tweak(x => x with { RoomSize = v }));
        _upmix = Toggle(s["SpatialUpmix"], v => app.Tweak(x => x with { SurroundUpmix = v }), s["SpatialUpmixHelp"]);
        _headTracking = Toggle(s["HeadTracking"], _ => { });
        _headTracking.IsEnabled = false;

        for (var i = 0; i < 10; i++)
        {
            var band = i;
            var f = SoundSettings.EqBands[i];
            var label = f >= 1000 ? $"{f / 1000:0} kHz" : $"{f:0} Hz";
            _eq[i] = Slider(label, -12, 12, 0.5, Db, v => app.Tweak(x => x.WithEqBand(band, v)));
        }
        _bass = Slider(s["ToneBass"], -12, 12, 0.5, Db, v => app.Tweak(x => x with { BassDb = v }));
        _treble = Slider(s["ToneTreble"], -12, 12, 0.5, Db, v => app.Tweak(x => x with { TrebleDb = v }));
        _preamp = Slider(s["TonePreamp"], -24, 6, 0.5, Db, v => app.Tweak(x => x with { PreampDb = v }));
        _voice = Slider(s["ToneVoice"], 0, 1, 0.05, Pct, v => app.Tweak(x => x with { VoiceClarity = v }));
        _normalize = Toggle(s["ToneNormalize"], v => app.Tweak(x => x with { LoudnessNormalization = v }), s["ToneNormalizeHelp"]);
        _night = Toggle(s["ToneNight"], v => app.Tweak(x => x with { NightCompression = v }), s["ToneNightHelp"]);
        _loudComp = Toggle(s["ToneLoudComp"], v => app.Tweak(x => x with { LoudnessCompensation = v }), s["ToneLoudCompHelp"]);
        _crossfeed = Slider(s["Crossfeed"], 0, 1, 0.05, Pct, v => app.Tweak(x => x with { Crossfeed = v }));
        _psychoBass = Slider(s["PsychoBass"], 0, 1, 0.05, Pct, v => app.Tweak(x => x with { PsychoBass = v }));

        _correction = Toggle(s["CorrectionEnable"], v => app.Edit(x => x with { CorrectionEnabled = v }));
        _ir = Toggle(s["IrEnable"], v => app.Edit(x => x with { ConvolutionEnabled = v }));
        _irMix = Slider(s["IrMix"], 0, 1, 0.05, Pct, v => app.Edit(x => x with { ConvolutionMix = v }));
        _irRemove = Kit.Button(s["IrRemove"], app.ClearImpulse);
        _hearing = Toggle(s["HearingEnable"], v => app.Edit(x => x with { HearingEnabled = v }));
        _hearingStart = Kit.Button(s["HearingStart"], async () => await new HearingCheck(app).RunAsync(XamlRoot));
        _mono = Toggle(s["Mono"], v => app.Edit(x => x with { Mono = v }));
        _balance = Slider(s["Balance"], -1, 1, 0.05,
            v => v < -0.01 ? $"L {Pct(-v)}" : v > 0.01 ? $"R {Pct(v)}" : "C", v => app.Edit(x => x with { Balance = v }));

        var eqPanel = new StackPanel { Spacing = 0 };
        foreach (var e in _eq) eqPanel.Children.Add(e);

        Content = Page(
            Title(s["NavSound"]),
            _editing,
            _note,
            Section(s["ModesTitle"], _modes),
            Section(s["EnvTitle"], Caption(s["EnvHelp"]), _envs, _envDesc, _intensity,
                Row(_resetIntensity, Kit.Button(s["TryWithSample"], app.PlaySample))),
            Section(s["SpatialTitle"], _spatial, _width, _room, _upmix, _headTracking, Caption(s["HeadTrackingWindows"])),
            Section(s["EqTitle"], eqPanel, Kit.Button(s["EqReset"], () => app.Tweak(x => x with { EqGainsDb = new float[10] }))),
            Section(s["ToneTitle"], _bass, _treble, _preamp, _voice, _normalize, _night, _loudComp),
            Section(s["ExtrasTitle"], _crossfeed, Caption(s["CrossfeedHelp"]), _psychoBass, Caption(s["PsychoBassHelp"])),
            Section(s["CorrectionTitle"], Caption(s["CorrectionHelp"]), _correction, _correctionStatus,
                Kit.Button(s["CorrectionImport"], async () =>
                {
                    var path = await Files.PickAsync(window, [".txt"]);
                    if (path is null) return;
                    var bytes = Files.ReadBounded(path, 16 * 1024);
                    if (bytes is null) { Note(InfoBarSeverity.Warning, s["FileTooLarge"]); return; }
                    var r = app.ImportCorrection(bytes);
                    if (r.Ok) Note(InfoBarSeverity.Success, s.Format("CorrectionImported", r.Filters.Count));
                    else Note(InfoBarSeverity.Warning, s["CorrectionFailed"] + " " + r.Error);
                })),
            Section(s["IrTitle"], Caption(s["IrHelp"]), _ir, _irMix,
                Row(Kit.Button(s["IrLoad"], async () =>
                {
                    var path = await Files.PickAsync(window, [".wav"]);
                    if (path is null) return;
                    var bytes = Files.ReadBounded(path, AppController.MaxImpulseBytes);
                    if (bytes is null) { Note(InfoBarSeverity.Warning, s["FileTooLarge"]); return; }
                    Note(app.ImportImpulse(bytes) ? InfoBarSeverity.Success : InfoBarSeverity.Warning,
                        s[app.HasImpulse ? "IrLoaded" : "IrFailed"]);
                }), _irRemove)),
            Section(s["HearingTitle"], Caption(s["HearingHelp"]), _hearing, _hearingStart),
            Section(s["AccessTitle"], _mono, _balance));

        Loaded += (_, _) => { app.Changed += Refresh; Refresh(); };
        Unloaded += (_, _) => app.Changed -= Refresh;
    }

    private void Note(InfoBarSeverity severity, string message)
    {
        _note.Severity = severity;
        _note.Message = message;
        _note.IsOpen = true;
    }

    private void Refresh()
    {
        var x = _app.Settings;
        var s = UiStrings.S;
        var headphones = _app.Profile.Safety.IsHeadphones;
        _editing.Text = s.Format("EditingFor", s["Output_" + _app.Profile.Output]);
        _crossfeed.IsEnabled = headphones && !x.SpatialEnabled;
        _psychoBass.IsEnabled = _app.Profile.Output == OutputType.Speaker;
        _hearingStart.IsEnabled = headphones;
        _hearingStart.Content = s[headphones ? "HearingStart" : "HearingNeedsHeadphones"];
        _ir.IsEnabled = _irMix.IsEnabled = _irRemove.IsEnabled = _app.HasImpulse;
        if (ReferenceEquals(x, _shown)) return; // only touch settings controls when settings actually changed
        _shown = x;

        _modes.Set(Array.IndexOf(Modes, x.Mode));
        _envs.Set(Array.IndexOf(Envs, x.Environment));
        _envDesc.Text = s["EnvDesc_" + x.Environment];
        _intensity.IsEnabled = x.Environment != Env.Off;
        _intensity.Set(x.EnvironmentIntensity);
        _resetIntensity.IsEnabled = x.Environment != Env.Off && Math.Abs(x.EnvironmentIntensity - 0.5f) > 0.01f;
        _spatial.Set(x.SpatialEnabled);
        _width.Set(x.Width);
        _room.Set(x.RoomSize); _room.IsEnabled = x.SpatialEnabled;
        _upmix.Set(x.SurroundUpmix); _upmix.IsEnabled = x.SpatialEnabled;
        for (var i = 0; i < 10; i++) _eq[i].Set(x.EqGainsDb[i]);
        _bass.Set(x.BassDb); _treble.Set(x.TrebleDb); _preamp.Set(x.PreampDb); _voice.Set(x.VoiceClarity);
        _normalize.Set(x.LoudnessNormalization); _night.Set(x.NightCompression); _loudComp.Set(x.LoudnessCompensation);
        _crossfeed.Set(x.Crossfeed); _psychoBass.Set(x.PsychoBass);
        _correction.Set(x.CorrectionEnabled); _correction.IsEnabled = x.Correction.Count > 0;
        _correctionStatus.Text = x.Correction.Count == 0 ? s["CorrectionNone"] : s.Format("CorrectionLoaded", x.Correction.Count, Labels.Db(x.CorrectionPreampDb));
        _ir.Set(x.ConvolutionEnabled && _app.HasImpulse); _irMix.Set(x.ConvolutionMix);
        _hearing.Set(x.HearingEnabled); _hearing.IsEnabled = x.HearingCompensation.Count > 0;
        _mono.Set(x.Mono); _balance.Set(x.Balance);
    }
}
