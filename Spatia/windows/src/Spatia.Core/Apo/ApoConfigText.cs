using System.Globalization;
using System.Text;
using Spatia.Core.Models;

namespace Spatia.Core.Apo;

/// <summary>Formats an <see cref="ApoPlan"/> as Equalizer APO configuration text (spatia.txt). Pure, so it is unit-tested.</summary>
public static class ApoConfigText
{
    public const string Header = "# Spatia: generated automatically whenever you change a setting in Spatia. Edits here are overwritten.";

    /// <param name="plan">Plan from the engine.</param>
    /// <param name="impulseFile">IR file name relative to spatia.txt's folder (Convolution paths are relative to the
    /// file that contains them), or null for none.</param>
    /// <param name="impulseHeadroomDb">Pre-gain (&lt;= 0 dB) that keeps the level when the IR is applied.</param>
    /// <param name="label">Optional comment line (mode / output) for people reading the file.</param>
    public static string Build(ApoPlan plan, string? impulseFile, float impulseHeadroomDb, string? label = null)
    {
        var sb = new StringBuilder();
        sb.Append(Header).Append('\n');
        if (label is not null) sb.Append("# ").Append(label.ReplaceLineEndings(" ")).Append('\n');
        if (plan.Bypass)
        {
            sb.Append("# Spatia is off: no processing.\n");
            return sb.ToString();
        }
        sb.Append("Channel: all\n");
        sb.Append("Preamp: ").Append(N(Math.Min(0f, plan.PreampDb))).Append(" dB\n");
        foreach (var f in plan.Filters) sb.Append(Filter(f)).Append('\n');
        if (!IsIdentity(plan.PreMatrix)) sb.Append(Copy(plan.PreMatrix)).Append('\n');
        if (impulseFile is not null)
        {
            if (impulseFile.IndexOfAny(['\r', '\n', '/', '\\']) >= 0) throw new ArgumentException("Invalid impulse file name.", nameof(impulseFile));
            sb.Append("Channel: L R\n");
            if (impulseHeadroomDb < 0f) sb.Append("Preamp: ").Append(N(impulseHeadroomDb)).Append(" dB\n");
            sb.Append("Convolution: ").Append(impulseFile).Append('\n');
            sb.Append("Channel: all\n");
        }
        if (!IsIdentity(plan.PostMatrix)) sb.Append(Copy(plan.PostMatrix)).Append('\n');
        return sb.ToString();
    }

    public static string Filter(EqFilter f) => f.Type switch
    {
        FilterType.Peak => $"Filter: ON PK Fc {N(f.FreqHz)} Hz Gain {N(f.GainDb)} dB Q {N(f.Q)}",
        FilterType.LowShelf => $"Filter: ON LSC Fc {N(f.FreqHz)} Hz Gain {N(f.GainDb)} dB Q {N(f.Q)}",
        FilterType.HighShelf => $"Filter: ON HSC Fc {N(f.FreqHz)} Hz Gain {N(f.GainDb)} dB Q {N(f.Q)}",
        FilterType.HighPass => $"Filter: ON HPQ Fc {N(f.FreqHz)} Hz Q {N(f.Q)}",
        FilterType.LowPass => $"Filter: ON LPQ Fc {N(f.FreqHz)} Hz Q {N(f.Q)}",
        FilterType.BandPass => $"Filter: ON BP Fc {N(f.FreqHz)} Hz Q {N(f.Q)}",
        _ => throw new ArgumentOutOfRangeException(nameof(f)),
    };

    /// <summary>Equalizer APO's Copy reads all sources before writing, so both outputs use the original L and R.</summary>
    public static string Copy(IReadOnlyList<float> m) =>
        $"Copy: L={N(m[0])}*L+{N(m[1])}*R R={N(m[2])}*L+{N(m[3])}*R";

    public static bool IsIdentity(IReadOnlyList<float> m) =>
        m.Count != 4 || (Math.Abs(m[0] - 1) < 1e-6 && Math.Abs(m[1]) < 1e-6 && Math.Abs(m[2]) < 1e-6 && Math.Abs(m[3] - 1) < 1e-6);

    private static string N(float v) => (float.IsFinite(v) ? v : 0f).ToString("0.####", CultureInfo.InvariantCulture);
}
