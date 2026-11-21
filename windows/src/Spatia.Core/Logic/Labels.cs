using System.Globalization;

namespace Spatia.Core.Logic;

/// <summary>Small display helpers shared by the UI and tests.</summary>
public static class Labels
{
    /// <summary>"Default", "Subtle −40%", "Intense +50%": distance from the designed default, as in the Android app.</summary>
    public static string Intensity(float v, string subtle, string @default, string intense)
    {
        var d = v - 0.5f;
        if (Math.Abs(d) < 0.01f) return @default;
        var pct = (int)MathF.Round(Math.Abs(d) / 0.5f * 100);
        var n = pct.ToString(CultureInfo.InvariantCulture);
        return d < 0 ? subtle + " −" + n + "%" : intense + " +" + n + "%";
    }

    public static string Db(float v) => v.ToString("+0.0;-0.0;0.0", CultureInfo.InvariantCulture) + " dB";

    public static string Time(TimeSpan t) => t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss", CultureInfo.InvariantCulture) : t.ToString(@"m\:ss", CultureInfo.InvariantCulture);
}
