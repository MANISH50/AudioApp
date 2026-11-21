using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Spatia.Core.Apo;
using Spatia.Core.Native;
using static Spatia.UI.Ui.Kit;

namespace Spatia.UI.Pages;

/// <summary>What this PC and its outputs support, and why any option is unavailable.</summary>
public sealed partial class StatusPage : UserControl
{
    private enum St { Available, Limited, Unavailable }

    public StatusPage(AppController app)
    {
        var s = UiStrings.S;
        var os = System.Environment.OSVersion.Version;
        var rows = new (string Title, St Status, string Reason)[]
        {
            (s["CapWindows"], St.Available, $"Windows {os.Major}.{os.Minor} (build {os.Build})"),
            (s["CapEngine"], St.Available, s.Format("CapEngineReason", Engine.ApiVersion)),
            (s["CapApo"], app.Apo.State switch { ApoState.Connected => St.Available, ApoState.InstalledNotConnected => St.Limited, _ => St.Unavailable },
                s[app.Apo.State switch { ApoState.Connected => "CapApoConnected", ApoState.InstalledNotConnected => "CapApoNotConnected", _ => "CapApoMissing" }]),
            (s["CapEnvironments"], St.Available, s["CapEnvironmentsReason"]),
            (s["CapSpatial"], St.Limited, s["CapSpatialReason"]),
            (s["CapHeadTracking"], St.Unavailable, s["HeadTrackingWindows"]),
            (s["CapDynamics"], St.Limited, s["CapDynamicsReason"]),
            (s["CapOtherApps"], app.Apo.State == ApoState.Connected ? St.Available : St.Unavailable, s["CapOtherAppsReason"]),
            (s["CapDevices"], app.Devices.Outputs.Count > 0 ? St.Available : St.Unavailable,
                string.Join(", ", app.Devices.Outputs.Select(o => $"{o.Name} ({s["Output_" + o.Type]})")) is { Length: > 0 } d ? d : s["CapNoDevices"]),
        };
        var list = new StackPanel { Spacing = 8 };
        foreach (var (title, status, reason) in rows)
        {
            var label = s["Status_" + status];
            var item = new StackPanel { Spacing = 2 };
            item.Children.Add(new TextBlock { Text = $"{Glyph(status)}  {title} — {label}" }.Style("BodyStrongTextBlockStyle"));
            item.Children.Add(Caption(reason));
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(item, $"{title}. {label}. {reason}");
            list.Children.Add(item);
        }
        Content = Page(Title(s["NavStatus"]), Section(s["StatusTitle"], list));
    }

    private static string Glyph(St s) => s switch { St.Available => "✔", St.Limited => "◐", _ => "✖" };
}
