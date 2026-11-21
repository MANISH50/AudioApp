using Spatia.Core.Models;

namespace Spatia.Core.Logic;

/// <summary>Half-gain compensation from listening-check thresholds, capped at +6 dB (same rule as Android).</summary>
public static class HearingProfile
{
    public const float MaxBoostDb = 6f;

    public static float[] Compensation(IReadOnlyList<float> leftDb, IReadOnlyList<float> rightDb)
    {
        var n = SoundSettings.HearingFrequencies.Length;
        if (leftDb.Count != n || rightDb.Count != n) throw new ArgumentException($"Need {n} thresholds per ear");
        var avg = Enumerable.Range(0, n).Select(i => (leftDb[i] + rightDb[i]) / 2f).ToArray();
        var best = avg.Min();
        return avg.Select(a => Math.Clamp((a - best) * 0.5f, 0f, MaxBoostDb)).ToArray();
    }
}
