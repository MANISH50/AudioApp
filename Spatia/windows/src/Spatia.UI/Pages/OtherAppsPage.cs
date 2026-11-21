using Microsoft.UI.Xaml.Controls;
using Spatia.UI.Ui;
using static Spatia.UI.Ui.Kit;

namespace Spatia.UI.Pages;

/// <summary>Explains how Spatia reaches every app (Equalizer APO), how to set it up, and what is approximated.</summary>
public sealed partial class OtherAppsPage : UserControl
{
    public OtherAppsPage()
    {
        var s = UiStrings.S;
        Content = Page(
            Title(s["NavOtherApps"]),
            Section(s["OtherWhyTitle"], Body(s["OtherWhy"])),
            Section(s["OtherNextTitle"], Body(s["OtherNext"])),
            Section(s["OtherNowTitle"], Body(s["OtherNow"])),
            Section(s["ApoTitle"], Kit.Button(s["ApoDownload"], AppController.OpenApoDownload)));
    }
}
