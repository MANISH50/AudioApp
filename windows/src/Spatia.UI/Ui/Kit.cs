using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;

namespace Spatia.UI.Ui;

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

    /// <summary>A card-like section with a heading.</summary>
    public static Border Section(string heading, params UIElement[] children)
    {
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(Heading(heading));
        foreach (var c in children) panel.Children.Add(c);
        var border = new Border
        {
            Child = panel,
            Padding = new Thickness(16),
            CornerRadius = new CornerRadius(8),
            BorderThickness = new Thickness(1),
        };
        if (Application.Current.Resources.TryGetValue("CardBackgroundFillColorDefaultBrush", out var bg)) border.Background = (Microsoft.UI.Xaml.Media.Brush)bg;
        if (Application.Current.Resources.TryGetValue("CardStrokeColorDefaultBrush", out var st)) border.BorderBrush = (Microsoft.UI.Xaml.Media.Brush)st;
        return border;
    }

    public static ScrollViewer Page(params UIElement[] children)
    {
        var panel = new StackPanel { Spacing = 12, Padding = new Thickness(24, 16, 24, 24), MaxWidth = 900, HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var c in children) panel.Children.Add(c);
        return new ScrollViewer { Content = panel };
    }

    public static StackPanel Row(params UIElement[] children)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static Button Button(string text, Action click, bool accent = false)
    {
        var b = new Button { Content = text };
        if (accent) b.Style("AccentButtonStyle");
        AutomationProperties.SetName(b, text);
        b.Click += (_, _) => click();
        return b;
    }

    public static ToggleSwitch Toggle(string header, Action<bool> changed, string? description = null)
    {
        var t = new ToggleSwitch { Header = header };
        AutomationProperties.SetName(t, header);
        if (description is not null) AutomationProperties.SetHelpText(t, description);
        t.Toggled += (_, _) => { if (!Updating.Active) changed(t.IsOn); };
        return t;
    }

    /// <summary>
    /// Slider that commits 300 ms after the user stops moving it (the engine ramps between committed values, so
    /// there are no clicks and settings aren't written on every pixel).
    /// </summary>
    public static Slider Slider(string header, double min, double max, double step, Func<double, string> format, Action<float> commit)
    {
        var s = new Slider { Header = header, Minimum = min, Maximum = max, StepFrequency = step, SmallChange = step };
        s.ThumbToolTipValueConverter = new FuncConverter(v => format(v));
        AutomationProperties.SetName(s, header);
        var timer = DispatcherQueue.GetForCurrentThread().CreateTimer();
        timer.Interval = TimeSpan.FromMilliseconds(300);
        timer.IsRepeating = false;
        timer.Tick += (_, _) => commit((float)s.Value);
        s.ValueChanged += (_, _) =>
        {
            AutomationProperties.SetItemStatus(s, format(s.Value));
            if (Updating.Active) return;
            timer.Stop(); timer.Start();
        };
        return s;
    }

    public static void Set(this Slider s, double v) { using (Updating.Begin()) if (Math.Abs(s.Value - v) > 1e-6) s.Value = v; }
    public static void Set(this ToggleSwitch t, bool v) { using (Updating.Begin()) if (t.IsOn != v) t.IsOn = v; }
    public static void Set(this RadioButtons r, int i) { using (Updating.Begin()) if (r.SelectedIndex != i) r.SelectedIndex = i; }

    public static RadioButtons Choices(string header, IEnumerable<string> items, Action<int> selected, int columns = 1)
    {
        var r = new RadioButtons { Header = header, MaxColumns = columns };
        foreach (var i in items) r.Items.Add(i);
        AutomationProperties.SetName(r, header);
        r.SelectionChanged += (_, _) => { if (!Updating.Active && r.SelectedIndex >= 0) selected(r.SelectedIndex); };
        return r;
    }

    /// <summary>Suppresses change handlers while the UI is being refreshed from state.</summary>
    public static class Updating
    {
        [ThreadStatic] private static int _depth;
        public static bool Active => _depth > 0;
        public static IDisposable Begin() { _depth++; return new Scope(); }
        private sealed class Scope : IDisposable { public void Dispose() => _depth--; }
    }

    private sealed partial class FuncConverter(Func<double, string> f) : Microsoft.UI.Xaml.Data.IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, string language) =>
            value is double d ? f(d) : value?.ToString() ?? "";
        public object ConvertBack(object value, Type targetType, object parameter, string language) => throw new NotSupportedException();
    }
}
