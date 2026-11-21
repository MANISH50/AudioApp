namespace Spatia.Core.Models;

public sealed record EqFilter(FilterType Type, float FreqHz, float GainDb, float Q);

/// <summary>
/// Every user-adjustable sound parameter. Mirrors the shared engine's SoundSettings exactly (same JSON), and is
/// always validated by the engine (<see cref="Native.Engine.Validate"/>) before use.
/// </summary>
public sealed record SoundSettings
{
    public static readonly float[] EqBands = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
    public static readonly float[] HearingFrequencies = [250, 500, 1000, 2000, 3000, 4000, 6000, 8000];

    public Mode Mode { get; init; } = Mode.Music;
    public IReadOnlyList<float> EqGainsDb { get; init; } = new float[10];
    public float BassDb { get; init; }
    public float TrebleDb { get; init; }
    public float PreampDb { get; init; }
    public bool LoudnessNormalization { get; init; }
    public float VoiceClarity { get; init; }
    public bool SpatialEnabled { get; init; }
    public float Width { get; init; } = 1f;
    public float RoomSize { get; init; }
    public bool HeadTracking { get; init; }
    public bool NightCompression { get; init; }
    public float Crossfeed { get; init; }
    public float PsychoBass { get; init; }
    public bool LoudnessCompensation { get; init; }
    public bool SurroundUpmix { get; init; }
    public bool Mono { get; init; }
    public float Balance { get; init; }
    public IReadOnlyList<EqFilter> Correction { get; init; } = [];
    public float CorrectionPreampDb { get; init; }
    public bool CorrectionEnabled { get; init; }
    public IReadOnlyList<float> HearingCompensation { get; init; } = [];
    public bool HearingEnabled { get; init; }
    public bool ConvolutionEnabled { get; init; }
    public float ConvolutionMix { get; init; } = 0.3f;
    public bool Bypass { get; init; }
    public Environment Environment { get; init; } = Environment.Off;
    public float EnvironmentIntensity { get; init; } = 0.5f;

    public SoundSettings WithEqBand(int band, float gainDb)
    {
        var gains = EqGainsDb.ToArray();
        if (band >= 0 && band < gains.Length) gains[band] = gainDb;
        return this with { EqGainsDb = gains };
    }

    // Records compare lists by reference; compare contents so "unchanged" really means unchanged.
    public bool Equals(SoundSettings? other) => other is not null &&
        SpatiaJson.Serialize(this) == SpatiaJson.Serialize(other);

    public override int GetHashCode() => SpatiaJson.Serialize(this).GetHashCode(StringComparison.Ordinal);
}
