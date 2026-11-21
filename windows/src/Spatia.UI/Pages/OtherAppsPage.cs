using Microsoft.UI.Xaml.Controls;
using static Spatia.UI.Ui.Kit;

namespace Spatia.UI.Pages;

/// <summary>Explains honestly why other apps aren't enhanced yet, and what the next release adds.</summary>
public sealed partial class OtherAppsPage : UserControl
{
    public OtherAppsPage()
    {
        var s = UiStrings.S;
        Content = Page(
            Title(s["NavOtherApps"]),
            Section(s["OtherWhyTitle"], Body(s["OtherWhy"])),
            Section(s["OtherNextTitle"], Body(s["OtherNext"])),
            Section(s["OtherNowTitle"], Body(s["OtherNow"])));
    }
}
