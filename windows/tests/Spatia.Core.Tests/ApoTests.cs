using Spatia.Core.Apo;
using Spatia.Core.Models;
using Spatia.Core.Native;
using Xunit;
using Env = Spatia.Core.Models.Environment;

namespace Spatia.Core.Tests;

/// <summary>Equalizer APO integration: config text, connect/disconnect on a temp folder, rendered impulse responses.</summary>
public sealed class ApoTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "spatia-apo-" + Guid.NewGuid().ToString("N"));
    private string Config => Path.Combine(_dir, "config.txt");

    public ApoTests()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(Config, "Preamp: -3 dB\r\nInclude: example.txt\r\n");
    }

    public void Dispose()
    {
        if (Directory.Exists(_dir)) Directory.Delete(_dir, true);
    }

    [Fact]
    public void Connect_is_idempotent_and_disconnect_restores_the_file()
    {
        var original = File.ReadAllBytes(Config);
        Assert.False(ApoInstaller.HasInclude(_dir));
        ApoInstaller.Connect(_dir, null);
        ApoInstaller.Connect(_dir, null);
        var text = File.ReadAllText(Config);
        Assert.Equal(1, text.Split('\n').Count(l => l.Trim() == ApoInstaller.IncludeLine));
        Assert.StartsWith("Preamp: -3 dB\r\nInclude: example.txt\r\n", text, StringComparison.Ordinal);
        Assert.EndsWith(ApoInstaller.IncludeLine + "\r\n", text, StringComparison.Ordinal);
        Assert.True(ApoInstaller.HasInclude(_dir));
        Assert.Equal(original, File.ReadAllBytes(Path.Combine(_dir, ApoInstaller.BackupFile)));
        Assert.True(File.Exists(Path.Combine(_dir, "Spatia", "spatia.txt")));

        ApoInstaller.Disconnect(_dir);
        ApoInstaller.Disconnect(_dir);
        Assert.Equal(original, File.ReadAllBytes(Config));
        Assert.False(ApoInstaller.HasInclude(_dir));

        // The backup is taken once, from the user's original file.
        File.WriteAllText(Config, "Preamp: -1 dB");
        ApoInstaller.Connect(_dir, null);
        Assert.Equal(original, File.ReadAllBytes(Path.Combine(_dir, ApoInstaller.BackupFile)));
        Assert.Equal("Preamp: -1 dB\n" + ApoInstaller.IncludeLine + "\n", File.ReadAllText(Config));
    }

    [Fact]
    public void Connect_keeps_bom_and_non_ascii_bytes()
    {
        byte[] bom = [0xEF, 0xBB, 0xBF];
        var body = "# café\nPreamp: -2 dB\n"u8.ToArray();
        File.WriteAllBytes(Config, [.. bom, .. body]);
        ApoInstaller.Connect(_dir, null);
        ApoInstaller.Disconnect(_dir);
        Assert.Equal([.. bom, .. body], File.ReadAllBytes(Config));
    }

    [Fact]
    public void Locator_honours_override_and_reports_state()
    {
        var old = System.Environment.GetEnvironmentVariable(ApoLocator.EnvOverride);
        try
        {
            System.Environment.SetEnvironmentVariable(ApoLocator.EnvOverride, _dir);
            Assert.Equal(ApoState.InstalledNotConnected, ApoLocator.Detect().State);
            ApoInstaller.Connect(_dir, null);
            var st = ApoLocator.Detect();
            Assert.Equal(ApoState.Connected, st.State);
            Assert.Equal(Path.Combine(_dir, "Spatia"), st.SpatiaDir);
            System.Environment.SetEnvironmentVariable(ApoLocator.EnvOverride, Path.Combine(_dir, "missing"));
            Assert.Equal(ApoState.NotInstalled, ApoLocator.Detect().State);
        }
        finally { System.Environment.SetEnvironmentVariable(ApoLocator.EnvOverride, old); }
    }

    [Fact]
    public void Command_line_connects_and_disconnects()
    {
        var old = System.Environment.GetEnvironmentVariable(ApoLocator.EnvOverride);
        try
        {
            System.Environment.SetEnvironmentVariable(ApoLocator.EnvOverride, _dir);
            Assert.Null(ApoCommandLine.TryRun([]));
            Assert.Null(ApoCommandLine.TryRun(["--something"]));
            Assert.Equal(2, ApoCommandLine.TryRun(["--apo-connect", "not-a-sid"]));
            Assert.Equal(0, ApoCommandLine.TryRun(["--apo-connect"]));
            Assert.True(ApoInstaller.HasInclude(_dir));
            Assert.Equal(0, ApoCommandLine.TryRun(["--apo-disconnect"]));
            Assert.False(ApoInstaller.HasInclude(_dir));
        }
        finally { System.Environment.SetEnvironmentVariable(ApoLocator.EnvOverride, old); }
    }

    [Fact]
    public void Neutralize_turns_spatia_off_without_touching_config()
    {
        ApoInstaller.Connect(_dir, null);
        var spatia = Path.Combine(_dir, "Spatia");
        File.WriteAllText(Path.Combine(spatia, "spatia.txt"), "Preamp: -3 dB\nConvolution: env_1.wav\n");
        File.WriteAllBytes(Path.Combine(spatia, "env_1.wav"), [1, 2, 3]);
        var config = File.ReadAllText(Config);
        ApoInstaller.Neutralize(_dir);
        Assert.Equal(config, File.ReadAllText(Config));
        Assert.DoesNotContain("Preamp", File.ReadAllText(Path.Combine(spatia, "spatia.txt")), StringComparison.Ordinal);
        Assert.Empty(Directory.GetFiles(spatia, "env_*.wav"));
    }

    [Theory]
    [InlineData("S-1-5-21-1004336348-1177238915-682003330-512", true)]
    [InlineData("S-1-5-21-1-2-3-1001", true)]
    [InlineData("S-1-5-21-1-2-3-1001 & calc", false)]
    [InlineData("", false)]
    [InlineData("X-1-5", false)]
    public void Sid_validation(string sid, bool ok) => Assert.Equal(ok, ApoInstaller.IsValidSid(sid));

    [Fact]
    public void Config_text_has_preamp_filters_matrices_and_convolution()
    {
        var plan = new ApoPlan(false, -4.5f,
            [new EqFilter(FilterType.Peak, 1000, 3, 1.41f), new EqFilter(FilterType.LowShelf, 100, 2, 0.707f),
             new EqFilter(FilterType.HighShelf, 8000, -1.5f, 0.707f), new EqFilter(FilterType.HighPass, 80, 0, 0.707f),
             new EqFilter(FilterType.LowPass, 16000, 0, 0.707f)],
            [1.25f, -0.25f, -0.25f, 1.25f], [1, 0, 0, 1], true, []);
        var text = ApoConfigText.Build(plan, "env_0123.wav", -2f, "Music / Speaker");
        var lines = text.Split('\n');
        Assert.Contains("Preamp: -4.5 dB", lines);
        Assert.Contains("Filter: ON PK Fc 1000 Hz Gain 3 dB Q 1.41", lines);
        Assert.Contains("Filter: ON LSC Fc 100 Hz Gain 2 dB Q 0.707", lines);
        Assert.Contains("Filter: ON HSC Fc 8000 Hz Gain -1.5 dB Q 0.707", lines);
        Assert.Contains("Filter: ON HPQ Fc 80 Hz Q 0.707", lines);
        Assert.Contains("Filter: ON LPQ Fc 16000 Hz Q 0.707", lines);
        Assert.Contains("Copy: L=1.25*L+-0.25*R R=-0.25*L+1.25*R", lines);
        Assert.Contains("Convolution: env_0123.wav", lines);
        Assert.Contains("Preamp: -2 dB", lines);
        Assert.DoesNotContain(lines, l => l.StartsWith("Copy: L=1*L", StringComparison.Ordinal)); // identity post matrix omitted
        Assert.Throws<ArgumentException>(() => ApoConfigText.Build(plan, "..\\evil.wav", 0, null));

        var off = ApoConfigText.Build(plan with { Bypass = true }, null, 0, null);
        Assert.DoesNotContain("Filter", off, StringComparison.Ordinal);
        Assert.DoesNotContain("Preamp", off, StringComparison.Ordinal);
    }

    [Fact]
    public void Config_text_is_culture_invariant()
    {
        var old = System.Globalization.CultureInfo.CurrentCulture;
        try
        {
            System.Globalization.CultureInfo.CurrentCulture = new System.Globalization.CultureInfo("de-DE");
            Assert.Equal("Filter: ON PK Fc 62.5 Hz Gain -1.25 dB Q 0.5", ApoConfigText.Filter(new EqFilter(FilterType.Peak, 62.5f, -1.25f, 0.5f)));
        }
        finally { System.Globalization.CultureInfo.CurrentCulture = old; }
    }

    // ---------------- through the real engine ----------------

    [Fact]
    public void Engine_plan_has_headroom_and_matches_settings()
    {
        var s = Engine.Preset(Mode.Music, null) with { BassDb = 6 };
        var plan = Engine.ApoPlan(s, OutputType.Wired, 0);
        Assert.False(plan.Bypass);
        Assert.True(plan.PreampDb <= -6f, "boosts must be offset by negative preamp, got " + plan.PreampDb);
        Assert.Contains(plan.Filters, f => f.Type == FilterType.LowShelf && f.FreqHz == 100 && f.GainDb == 6);
        Assert.Equal(4, plan.PreMatrix.Count);
        Assert.True(Engine.ApoPlan(s with { Bypass = true }, OutputType.Wired, 0).Bypass);
        Assert.Contains(ApoPlan.ApproxNight, Engine.ApoPlan(Engine.Preset(Mode.Night, null), OutputType.Speaker, 0).Approximations);
    }

    [Fact]
    public void Engine_renders_a_sane_impulse_wav()
    {
        var (wav, headroom) = Engine.RenderApoImpulse(new SoundSettings { Environment = Env.Hall, EnvironmentIntensity = 0.7f }, []);
        Assert.Equal("RIFF", System.Text.Encoding.ASCII.GetString(wav, 0, 4));
        Assert.Equal(3, BitConverter.ToInt16(wav, 20));   // IEEE float
        Assert.Equal(2, BitConverter.ToInt16(wav, 22));   // stereo
        Assert.Equal(48000, BitConverter.ToInt32(wav, 24));
        Assert.Equal(32, BitConverter.ToInt16(wav, 34));
        var samples = new float[(wav.Length - 44) / 4];
        Buffer.BlockCopy(wav, 44, samples, 0, samples.Length * 4);
        var seconds = samples.Length / 2 / 48000.0;
        Assert.InRange(seconds, 2.0, 4.0);
        Assert.Equal(1f, samples[0]);
        Assert.Equal(1f, samples[1]);
        Assert.All(samples, v => Assert.True(float.IsFinite(v)));
        Assert.True(samples.Skip(2).Sum(MathF.Abs) > 1f, "room tail expected");
        Assert.True(headroom < 0f);
    }

    [Fact]
    public void Writer_writes_config_and_impulse_and_cleans_up()
    {
        var spatia = Path.Combine(_dir, "Spatia");
        using var w = new ApoConfigWriter(spatia, debounceMs: 10);
        var hall = new SoundSettings { Environment = Env.Hall };
        w.Write(new ApoRequest(hall, OutputType.Speaker, 0));
        var first = File.ReadAllText(w.ConfigPath);
        var irs = Directory.GetFiles(spatia, "env_*.wav");
        Assert.Single(irs);
        Assert.Contains("Convolution: " + Path.GetFileName(irs[0]), first, StringComparison.Ordinal);

        w.Write(new ApoRequest(hall with { EnvironmentIntensity = 0.9f, BassDb = 4 }, OutputType.Speaker, 0));
        var second = File.ReadAllText(w.ConfigPath);
        Assert.NotEqual(first, second);
        var irs2 = Directory.GetFiles(spatia, "env_*.wav");
        Assert.Single(irs2);
        Assert.NotEqual(irs[0], irs2[0]);

        w.Write(new ApoRequest(new SoundSettings(), OutputType.Speaker, 0));
        Assert.Empty(Directory.GetFiles(spatia, "env_*.wav"));
        Assert.DoesNotContain("Convolution", File.ReadAllText(w.ConfigPath), StringComparison.Ordinal);
    }

    [Fact]
    public void Writer_debounces_to_the_latest_request()
    {
        var spatia = Path.Combine(_dir, "Spatia");
        var calls = 0;
        using var done = new ManualResetEventSlim();
        using var w = new ApoConfigWriter(spatia, debounceMs: 100,
            plan: r => { Interlocked.Increment(ref calls); return new ApoPlan(false, -r.Settings.BassDb, [], [1, 0, 0, 1], [1, 0, 0, 1], false, []); });
        w.Written += _ => done.Set();
        for (var i = 1; i <= 5; i++) w.Schedule(new ApoRequest(new SoundSettings { BassDb = i }, OutputType.Speaker, 0));
        Assert.True(done.Wait(5000));
        Assert.Equal(1, calls);
        Assert.Contains("Preamp: -5 dB", File.ReadAllText(w.ConfigPath), StringComparison.Ordinal);
    }
}
