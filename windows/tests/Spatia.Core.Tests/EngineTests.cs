using Spatia.Core.Logic;
using Spatia.Core.Models;
using Spatia.Core.Native;
using Spatia.Core.Settings;
using Xunit;
using Env = Spatia.Core.Models.Environment;

namespace Spatia.Core.Tests;

/// <summary>Exercises the real shared engine through its C API, exactly as the Windows app does.</summary>
public class EngineTests
{
    [Fact]
    public void Api_version_matches()
    {
        Assert.Equal(Engine.ExpectedApiVersion, Engine.ApiVersion);
    }

    [Fact]
    public void Validation_clamps_and_survives_garbage()
    {
        var v = Engine.Validate(new SoundSettings { BassDb = 99, Width = float.NaN, EnvironmentIntensity = 7 });
        Assert.Equal(12f, v.BassDb);
        Assert.Equal(1f, v.Width);
        Assert.Equal(1f, v.EnvironmentIntensity);
        Assert.Equal(10, v.EqGainsDb.Count);
        Assert.Null(Engine.ValidateJson("{not json"));
        var unknown = Engine.ValidateJson("""{"environment":"SPACESHIP","bassDb":3}""");
        Assert.NotNull(unknown);
        Assert.Equal(Env.Off, unknown.Environment);
        Assert.Equal(3f, unknown.BassDb);
    }

    [Fact]
    public void Json_matches_engine_for_every_enum_value()
    {
        foreach (var env in Enum.GetValues<Env>())
            Assert.Equal(env, Engine.Validate(new SoundSettings { Environment = env }).Environment);
        foreach (var mode in Enum.GetValues<Mode>())
            Assert.Equal(mode, Engine.Preset(mode, null).Mode);
    }

    [Fact]
    public void Presets_and_output_defaults_come_from_shared_engine()
    {
        Assert.True(Engine.Preset(Mode.Night, null).NightCompression);
        Assert.True(Engine.Preset(Mode.Voice, null).VoiceClarity > 0.5f);
        var keepRoom = Engine.Preset(Mode.Movie, new SoundSettings { Environment = Env.Hall });
        Assert.Equal(Env.Hall, keepRoom.Environment);
        Assert.True(Engine.DefaultFor(OutputType.Speaker).PsychoBass > 0f);
    }

    [Fact]
    public void Safety_rules_are_stricter_for_headphones()
    {
        var spk = Engine.Safety(OutputType.Speaker);
        foreach (var t in new[] { OutputType.Wired, OutputType.Bluetooth, OutputType.Usb })
        {
            var hp = Engine.Safety(t);
            Assert.True(hp.IsHeadphones);
            Assert.True(hp.MaxBoostDb < spk.MaxBoostDb);
            Assert.True(hp.VolumeWarningFraction < spk.VolumeWarningFraction);
        }
        Assert.Equal(Engine.Safety(OutputType.Wired).MaxBoostDb, Engine.CapBoost(OutputType.Wired, 40f));
        Assert.Equal(0f, Engine.CapBoost(OutputType.Wired, float.NaN));
    }

    [Fact]
    public void Active_output_prefers_most_personal_device_and_respects_override()
    {
        Assert.Equal(OutputType.Speaker, Engine.ActiveOutput([], null));
        Assert.Equal(OutputType.Usb, Engine.ActiveOutput([OutputType.Speaker, OutputType.Bluetooth, OutputType.Usb], null));
        Assert.Equal(OutputType.HdmiCast, Engine.ActiveOutput([OutputType.Wired], OutputType.HdmiCast));
    }

    [Fact]
    public void AutoEq_parsing_goes_through_engine()
    {
        var ok = Engine.ParseAutoEq("Preamp: -6.4 dB\nFilter 1: ON LSC Fc 105 Hz Gain 5.6 dB Q 0.70\n"u8);
        Assert.True(ok.Ok);
        Assert.Equal(-6.4f, ok.PreampDb, 3);
        Assert.Equal(FilterType.LowShelf, Assert.Single(ok.Filters).Type);
        var bad = Engine.ParseAutoEq("hello"u8);
        Assert.False(bad.Ok);
        Assert.False(string.IsNullOrEmpty(bad.Error));
        Assert.False(Engine.ParseAutoEq(new byte[20_000]).Ok);
    }

    [Fact]
    public void Sample_track_renders_with_headroom()
    {
        var t = Engine.RenderSampleTrack(44100);
        Assert.Equal(44100 * 24 * 2, t.Length);
        var peak = t.Max(MathF.Abs);
        Assert.InRange(peak, 0.45f, 0.51f); // normalised to -6 dBFS
    }
}
