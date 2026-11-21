using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace ScreenLink.UI.Ui;

/// <summary>Small builders so pages read like layouts. Every interactive control gets an accessible name.</summary>
public static class Kit
{
    public static T Style<T>(this T e, string key) where T : FrameworkElement
    {
        if (Application.Current.Resources.TryGetValue(key, out var s) && s is Style style) e.Style = style;
        return e;
    }

    public static TextBlock Title(string text) => new TextBlock { Text = text }.Style("TitleTextBlockStyle");
    public static TextBlock Heading(string text)
    {
        var t = new TextBlock { Text = text, Margin = new Thickness(0, 4, 0, 0) }.Style("SubtitleTextBlockStyle");
        AutomationProperties.SetHeadingLevel(t, Microsoft.UI.Xaml.Automation.Peers.AutomationHeadingLevel.Level2);
        return t;
    }
    public static TextBlock Body(string text) => new() { Text = text, TextWrapping = TextWrapping.WrapWholeWords };
    public static TextBlock Caption(string text) =>
        new TextBlock { Text = text, TextWrapping = TextWrapping.WrapWholeWords, Opacity = 0.8 }.Style("CaptionTextBlockStyle");

    public static Border Section(string heading, params UIElement[] children)
    {
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(Heading(heading));
        foreach (var c in children) panel.Children.Add(c);
        var border = new Border { Child = panel, Padding = new Thickness(16), CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1) };
        if (Application.Current.Resources.TryGetValue("CardBackgroundFillColorDefaultBrush", out var bg)) border.Background = (Microsoft.UI.Xaml.Media.Brush)bg;
        if (Application.Current.Resources.TryGetValue("CardStrokeColorDefaultBrush", out var st)) border.BorderBrush = (Microsoft.UI.Xaml.Media.Brush)st;
        return border;
    }

    public static Button Button(string text, Action click, bool accent = false)
    {
        var b = new Button { Content = text };
        if (accent) b.Style("AccentButtonStyle");
        AutomationProperties.SetName(b, text);
        b.Click += (_, _) => click();
        return b;
    }

    public static StackPanel Row(params UIElement[] children)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var c in children) p.Children.Add(c);
        return p;
    }
}
