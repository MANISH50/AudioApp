using Microsoft.UI.Xaml.Controls;
using static Spatia.App.Ui.Kit;

namespace Spatia.App.Pages;

/// <summary>Explains honestly why other apps aren't enhanced yet, and what the next release adds.</summary>
public sealed partial class OtherAppsPage : UserControl
{
    public OtherAppsPage()
    {
        var s = App.S;
        Content = Page(
            Title(s["NavOtherApps"]),
            Section(s["OtherWhyTitle"], Body(s["OtherWhy"])),
            Section(s["OtherNextTitle"], Body(s["OtherNext"])),
            Section(s["OtherNowTitle"], Body(s["OtherNow"])));
    }
}
