using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Spatia.Core.Models;
using static Spatia.App.Ui.Kit;

namespace Spatia.App.Pages;

/// <summary>
/// Ascending-level listening check: per ear and frequency, the tone starts very quiet and rises in 5 dB steps until
/// heard. Relative result only; not a medical test; stays on this PC.
/// </summary>
public sealed class HearingCheck(AppController app)
{
    private const float StartDb = -75f, MaxDb = -20f;
    private readonly List<float> _left = [], _right = [];
    private int _step;
    private float _level = StartDb;

    public async Task RunAsync(XamlRoot? root)
    {
        if (root is null) return;
        var s = App.S;
        var freqs = SoundSettings.HearingFrequencies;
        var progress = new ProgressBar { Minimum = 0, Maximum = freqs.Length * 2 };
        var what = Body(s["HearingInstructions"]);
        var level = Caption("");
        var buttons = Row();
        var panel = new StackPanel { Spacing = 12, Children = { what, progress, level, buttons } };
        var dialog = new ContentDialog
        {
            XamlRoot = root, Title = s["HearingTitle"], Content = panel,
            PrimaryButtonText = s["HearingBegin"], CloseButtonText = s["Cancel"],
        };

        bool LeftEar() => _step < freqs.Length;
        float Freq() => freqs[_step % freqs.Length];
        void Play() => app.Tones.Play(Freq(), _level, LeftEar());
        void Show()
        {
            progress.Value = _step;
            what.Text = s.Format(LeftEar() ? "HearingLeft" : "HearingRight", (int)Freq());
            level.Text = s.Format("HearingLevel", (int)_level);
        }
        void Record(float v)
        {
            (LeftEar() ? _left : _right).Add(v);
            _level = StartDb;
            if (++_step >= freqs.Length * 2)
            {
                app.Tones.Stop();
                app.SaveHearingResult(_left, _right);
                dialog.Hide();
                return;
            }
            Show(); Play();
        }

        buttons.Children.Add(Spatia.App.Ui.Kit.Button(s["HearingHeard"], () => Record(_level), accent: true));
        buttons.Children.Add(Spatia.App.Ui.Kit.Button(s["HearingLouder"], () => { if (_level >= MaxDb) Record(MaxDb); else { _level += 5; Show(); Play(); } }));
        buttons.Children.Add(Spatia.App.Ui.Kit.Button(s["HearingReplay"], Play));
        buttons.Visibility = Visibility.Collapsed;

        dialog.PrimaryButtonClick += (_, e) =>
        {
            e.Cancel = true; // keep the dialog open; the check runs inside it
            dialog.PrimaryButtonText = "";
            buttons.Visibility = Visibility.Visible;
            Show(); Play();
        };
        await dialog.ShowAsync();
        app.Tones.Stop();
    }
}
