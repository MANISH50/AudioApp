using Spatia.Core.Models;
using Spatia.Core.Native;
using Xunit;
using Env = Spatia.Core.Models.Environment;

namespace Spatia.Core.Tests;

public class ChainTests
{
    private const int Sr = 44100;

    /// <summary>Energy in the silent gaps of the sample's claps section (14–20 s), skipping limiter latency.</summary>
    private static double GapEnergy(float[] stereo)
    {
        double[] hits = [14.0, 15.5, 17.0, 18.5];
        double e = 0;
        for (var k = 0; k < hits.Length - 1; k++)
            for (var i = (int)((hits[k] + 0.37) * Sr); i < (int)(hits[k + 1] * Sr); i++)
                e += stereo[2 * i] * stereo[2 * i] + stereo[2 * i + 1] * stereo[2 * i + 1];
        return e;
    }

    private static float[] Run(SoundSettings s, OutputType output = OutputType.Wired)
    {
        var input = Engine.RenderSampleTrack(Sr);
        var output2 = new float[input.Length];
        using var chain = new DspChain(Sr, 2);
        chain.SetSettings(s, output);
        const int block = 1024;
        for (var f = 0; f < input.Length / 2; f += block)
        {
            var n = Math.Min(block, input.Length / 2 - f);
            chain.Process(input.AsSpan(f * 2, n * 2), output2.AsSpan(f * 2, n * 2), n);
        }
        return output2;
    }

    [Fact]
    public void Off_leaves_sample_gaps_silent()
    {
        Assert.Equal(0, GapEnergy(Run(new SoundSettings())), 6);
    }

    [Theory]
    [InlineData(Env.Hall)]
    [InlineData(Env.Cinema)]
    [InlineData(Env.Echoing)]
    [InlineData(Env.Bathroom)]
    [InlineData(Env.Cathedral)]
    public void Environments_fill_the_gaps(Env env)
    {
        Assert.True(GapEnergy(Run(new SoundSettings { Environment = env })) > 1e-3);
    }

    [Fact]
    public void Intensity_meter_orders_subtle_default_intense()
    {
        var subtle = GapEnergy(Run(new SoundSettings { Environment = Env.Hall, EnvironmentIntensity = 0.25f }));
        var normal = GapEnergy(Run(new SoundSettings { Environment = Env.Hall }));
        var intense = GapEnergy(Run(new SoundSettings { Environment = Env.Hall, EnvironmentIntensity = 1f }));
        Assert.True(subtle < normal && normal < intense, $"{subtle} {normal} {intense}");
    }

    [Fact]
    public void Limiter_holds_ceiling_on_every_output()
    {
        var extreme = new SoundSettings
        {
            EqGainsDb = Enumerable.Repeat(12f, 10).ToArray(), BassDb = 12, PreampDb = 6,
            Environment = Env.Cathedral, EnvironmentIntensity = 1, SpatialEnabled = true,
        };
        foreach (var o in Enum.GetValues<OutputType>())
        {
            var y = Run(extreme, o);
            var ceiling = MathF.Pow(10, Engine.Safety(o).LimiterCeilingDb / 20);
            Assert.True(y.All(v => float.IsFinite(v) && MathF.Abs(v) <= ceiling + 1e-6f), o.ToString());
        }
    }

    [Fact]
    public void Modes_sound_different()
    {
        var music = Run(Engine.Preset(Mode.Music, null));
        var voice = Run(Engine.Preset(Mode.Voice, null));
        Assert.False(music.SequenceEqual(voice));
    }

    [Fact]
    public void Meters_and_spectrum_report_after_processing()
    {
        using var chain = new DspChain(Sr, 2);
        chain.SetSettings(new SoundSettings(), OutputType.Speaker);
        var x = Enumerable.Range(0, 4096 * 2).Select(i => MathF.Sin(i * 0.05f) * 0.3f).ToArray();
        chain.Process(x, new float[x.Length], 4096);
        Assert.InRange(chain.Meters().PeakDb, -20f, 0f);
        Assert.Equal(24, chain.Spectrum().Length);
    }

    [Fact]
    public void Invalid_arguments_are_rejected_safely()
    {
        Assert.Throws<NotSupportedException>(() => new DspChain(Sr, 3));
        Assert.False(DspChain.Supports(1000, 2));
        using var chain = new DspChain(Sr, 2);
        Assert.Throws<ArgumentException>(() => chain.Process(new float[10], new float[10], 100));
        Assert.False(chain.SetImpulse("not a wav file at all, definitely not RIFF"u8));
        Assert.True(chain.SetImpulse(ReadOnlySpan<byte>.Empty));
        chain.Dispose();
        Assert.Throws<ObjectDisposedException>(() => chain.SetSettings(new SoundSettings(), OutputType.Wired));
    }
}
