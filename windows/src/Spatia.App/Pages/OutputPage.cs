using Microsoft.UI.Xaml.Controls;
using Spatia.Core.Logic;
using Spatia.Core.Models;
using static Spatia.App.Ui.Kit;

namespace Spatia.App.Pages;

public sealed partial class OutputPage : UserControl
{
    private readonly AppController _app;
    private readonly TextBlock _active = new TextBlock().Style("SubtitleTextBlockStyle");
    private readonly TextBlock _how = Caption("");
    private readonly TextBlock _safety = Body("");
    private readonly TextBlock _volume = Caption("");
    private readonly TextBlock _devices = Caption("");
    private readonly RadioButtons _override;
    private readonly ToggleSwitch _remember, _exclusive, _dose;
    private readonly TextBlock _doseText = Body("");
    private static readonly OutputType[] Types = Enum.GetValues<OutputType>();

    public OutputPage(AppController app)
    {
        _app = app;
        var s = App.S;
        _override = Choices(s["OutputProfile"], new[] { s["OutputAuto"] }.Concat(Types.Select(t => s["Output_" + t])),
            i => app.SetManualOutput(i == 0 ? null : Types[i - 1]));
        _remember = Toggle(s["OutputRemember"], app.SetRememberPerDevice, s["OutputRememberHelp"]);
        _exclusive = Toggle(s["ExclusiveEnable"], app.SetExclusiveMode, s["ExclusiveHelp"]);
        _dose = Toggle(s["DoseEnable"], app.SetDoseTracking, s["DoseHelp"]);

        Content = Page(
            Title(s["NavOutput"]),
            Section(s["OutputActive"], _active, _how, _safety, _volume, _devices),
            Section(s["OutputProfile"], _override, _remember),
            Section(s["ExclusiveTitle"], _exclusive, Caption(s["ExclusiveHelp"])),
            Section(s["DoseTitle"], _dose, Caption(s["DoseHelp"]), _doseText));

        Loaded += (_, _) => { app.Changed += Refresh; Refresh(); };
        Unloaded += (_, _) => app.Changed -= Refresh;
    }

    private void Refresh()
    {
        var s = App.S;
        var p = _app.Profile;
        _active.Text = s["Output_" + p.Output] + (_app.Devices.DefaultDeviceName is { } n ? $" — {n}" : "");
        _how.Text = s[_app.State.ManualOutput is null ? "OutputDetected" : "OutputManual"];
        _safety.Text = s.Format("OutputSafety", Labels.Db(p.Safety.MaxBoostDb), p.Safety.LimiterCeilingDb, (int)(p.Safety.VolumeWarningFraction * 100));
        _volume.Text = s.Format("OutputVolume", (int)(_app.Volume.Fraction * 100), (int)_app.Volume.AttenuationDb);
        _devices.Text = s["OutputConnected"] + " " + string.Join(", ", _app.Devices.Outputs.Select(o => $"{o.Name} ({s["Output_" + o.Type]})"));
        _override.Set(_app.State.ManualOutput is { } m ? Array.IndexOf(Types, m) + 1 : 0);
        _remember.Set(_app.State.RememberPerDevice);
        _exclusive.Set(_app.Player.ExclusiveMode);
        _dose.Set(_app.State.DoseTrackingEnabled);
        _doseText.Text = _app.State.DoseTrackingEnabled ? s.Format("DoseValue", (int)(_app.State.DoseFraction * 100)) : "";
    }
}
