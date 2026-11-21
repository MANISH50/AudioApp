using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Spatia.Core.Apo;
using Spatia.Core.Models;
using Spatia.UI.Ui;
using static Spatia.UI.Ui.Kit;

namespace Spatia.UI.Pages;

/// <summary>
/// First page: Equalizer APO status with the one action that fits it (download, connect, or active + disconnect),
/// the mode choice, Spatia on/off and the Test sound button.
/// </summary>
public sealed partial class HomePage : UserControl
{
    private readonly AppController _app;
    private readonly TextBlock _apoState = Body("").Style("BodyStrongTextBlockStyle");
    private readonly TextBlock _devices = Caption("");
    private readonly Button _download, _connect, _disconnect, _recheck, _deviceSelector;
    private readonly InfoBar _note = new() { IsClosable = true };
    private readonly RadioButtons _modes;
    private readonly ToggleSwitch _power;
    private readonly TextBlock _applies = Caption("");
    private static readonly Mode[] Modes = Enum.GetValues<Mode>();

    public HomePage(AppController app)
    {
        _app = app;
        var s = UiStrings.S;
        _download = Kit.Button(s["ApoDownload"], AppController.OpenApoDownload, accent: true);
        _connect = Kit.Button(s["ApoConnect"], async () => await RunAsync(connect: true), accent: true);
        _disconnect = Kit.Button(s["ApoDisconnect"], async () => await RunAsync(connect: false));
        _recheck = Kit.Button(s["ApoRecheck"], app.RefreshApo);
        _deviceSelector = Kit.Button(s["ApoOpenDeviceSelector"], () => app.OpenDeviceSelector());
        AutomationProperties.SetAutomationId(_download, "apo-download");
        AutomationProperties.SetAutomationId(_connect, "apo-connect");
        AutomationProperties.SetAutomationId(_disconnect, "apo-disconnect");
        AutomationProperties.SetLiveSetting(_apoState, Microsoft.UI.Xaml.Automation.Peers.AutomationLiveSetting.Polite);

        _modes = Choices(s["ModesTitle"], Modes.Select(m => s["Mode_" + m]), i => app.ApplyMode(Modes[i]), columns: 3);
        _power = Toggle(s["PowerToggle"], on => app.Edit(x => x with { Bypass = !on }), s["PowerHelp"]);
        AutomationProperties.SetAutomationId(_power, "power");
        var test = Kit.Button(s["TestSound"], app.PlayTestSound, accent: true);
        AutomationProperties.SetAutomationId(test, "test-sound");

        Content = Page(
            Title(s["AppTitle"]),
            Body(s["HomeIntro"]),
            _note,
            Section(s["ApoTitle"], _apoState, Row(_download, _connect, _disconnect, _recheck), Caption(s["ApoDeviceReminder"]),
                _devices, _deviceSelector),
            Section(s["PowerTitle"], _power, _applies),
            Section(s["ModesTitle"], _modes),
            Section(s["TestTitle"], Caption(s["TestHelp"]), test));

        Loaded += (_, _) => { app.Changed += Refresh; Refresh(); };
        Unloaded += (_, _) => app.Changed -= Refresh;
    }

    private async Task RunAsync(bool connect)
    {
        var s = UiStrings.S;
        var button = connect ? _connect : _disconnect;
        button.IsEnabled = false;
        try
        {
            var ok = connect ? await _app.ConnectApoAsync() : await _app.DisconnectApoAsync();
            if (!ok) Note(InfoBarSeverity.Warning, s[connect ? "ApoConnectFailed" : "ApoDisconnectFailed"]);
        }
        finally { button.IsEnabled = true; }
    }

    private void Note(InfoBarSeverity severity, string message)
    {
        _note.Severity = severity;
        _note.Message = message;
        _note.IsOpen = true;
    }

    private void Refresh()
    {
        var s = UiStrings.S;
        var apo = _app.Apo;
        _apoState.Text = s["Apo_" + apo.State];
        static Visibility Show(bool v) => v ? Visibility.Visible : Visibility.Collapsed;
        _download.Visibility = Show(apo.State == ApoState.NotInstalled);
        _recheck.Visibility = Show(apo.State == ApoState.NotInstalled);
        _connect.Visibility = Show(apo.State == ApoState.InstalledNotConnected);
        _disconnect.Visibility = Show(apo.State == ApoState.Connected);
        _devices.Visibility = Show(apo.State != ApoState.NotInstalled);
        _devices.Text = apo.Devices.Count > 0 ? s.Format("ApoDevices", string.Join(", ", apo.Devices)) : s["ApoNoDevices"];
        _deviceSelector.Visibility = Show(apo.DeviceSelectorPath is { } p && File.Exists(p));
        if (_app.ApoError is { } err) Note(InfoBarSeverity.Warning, s.Format("ApoWriteFailed", err));
        else if (_app.TestSoundError is not null) Note(InfoBarSeverity.Warning, s["TestSoundFailed"]);

        var x = _app.Settings;
        _modes.Set(Array.IndexOf(Modes, x.Mode));
        _power.Set(!x.Bypass);
        _applies.Text = apo.State == ApoState.Connected ? s["SoundAppliesAll"] : s["ApoNotConnectedNote"];
    }
}
