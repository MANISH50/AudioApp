namespace Spatia.Core.Logic;

/// <summary>
/// Weekly sound-dose estimate (WHO/ITU H.870: 80 dB(A) for 40 h/week, 3 dB exchange rate). Same formula as the Android
/// engine; the result is guidance only, because the app can't know the headphones' real sensitivity.
/// </summary>
public static class DoseTracker
{
    public static double DoseFraction(double splDb, double seconds)
    {
        if (seconds <= 0 || !double.IsFinite(splDb)) return 0;
        var allowedHours = 40.0 / Math.Pow(2, (splDb - 80.0) / 3.0);
        return seconds / 3600.0 / allowedHours;
    }

    public static double EstimateSpl(float volumeFraction, float rmsDbfs, double fullScaleSpl = 100)
    {
        var v = Math.Clamp(volumeFraction, 0f, 1f);
        return v == 0 ? 0 : fullScaleSpl + (v - 1f) * 60.0 + rmsDbfs;
    }

    public static long WeekNumber(DateTimeOffset now) => now.ToUnixTimeSeconds() / (7 * 24 * 3600);
}
