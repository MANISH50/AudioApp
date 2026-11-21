using Spatia.Core.Logic;
using Spatia.Core.Models;
using Spatia.Core.Settings;
using Xunit;
using Env = Spatia.Core.Models.Environment;

namespace Spatia.Core.Tests;

public class LogicTests
{
    [Theory]
    [InlineData(EndpointFormFactor.Speakers, "HDAUDIO", "Speakers (Realtek)", OutputType.Speaker)]
    [InlineData(EndpointFormFactor.Headphones, "HDAUDIO", "Headphones (Realtek)", OutputType.Wired)]
    [InlineData(EndpointFormFactor.Headphones, "BTHENUM", "WH-1000XM5", OutputType.Bluetooth)]
    [InlineData(EndpointFormFactor.Headset, "BTHLEAUDIO", "Buds", OutputType.Bluetooth)]
    [InlineData(EndpointFormFactor.Headphones, "USB", "USB DAC", OutputType.Usb)]
    [InlineData(EndpointFormFactor.DigitalAudioDisplayDevice, "HDAUDIO", "LG TV", OutputType.HdmiCast)]
    [InlineData(EndpointFormFactor.UnknownFormFactor, "SWD", "Speakers (Bluetooth)", OutputType.Bluetooth)]
    public void Endpoints_are_classified(EndpointFormFactor ff, string bus, string name, OutputType expected)
    {
        Assert.Equal(expected, OutputClassifier.Classify(ff, bus, name));
    }

    [Fact]
    public void Device_keys_are_stable_hashes()
    {
        var k = OutputClassifier.DeviceKey(OutputType.Bluetooth, "Buds");
        Assert.Equal(k, OutputClassifier.DeviceKey(OutputType.Bluetooth, "Buds"));
        Assert.NotEqual(k, OutputClassifier.DeviceKey(OutputType.Bluetooth, "Other"));
        Assert.Matches("^[0-9a-f]{16}$", k);
    }

    [Theory]
    [InlineData("song.flac", FormatVerdict.Supported)]
    [InlineData("song.MP3", FormatVerdict.Supported)]
    [InlineData("song.m4a", FormatVerdict.Supported)]
    [InlineData("song.wav", FormatVerdict.Supported)]
    [InlineData("song.opus", FormatVerdict.OpusDecoder)]
    [InlineData("film.iamf", FormatVerdict.IamfUnsupported)]
    [InlineData("song.dsf", FormatVerdict.KnownUnsupported)]
    [InlineData("photo.png", FormatVerdict.NotAudio)]
    [InlineData("what.xyz", FormatVerdict.Unknown)]
    public void Formats_are_classified(string file, FormatVerdict expected)
    {
        Assert.Equal(expected, FormatSupport.Classify(file));
    }

    [Fact]
    public void Labels()
    {
        Assert.Equal("Default", Logic.Labels.Intensity(0.5f, "Subtle", "Default", "Intense"));
        Assert.Equal("Subtle −100%", Logic.Labels.Intensity(0f, "Subtle", "Default", "Intense"));
        Assert.Equal("Intense +50%", Logic.Labels.Intensity(0.75f, "Subtle", "Default", "Intense"));
        Assert.Equal("+3.0 dB", Logic.Labels.Db(3f));
        Assert.Equal("1:05", Logic.Labels.Time(TimeSpan.FromSeconds(65)));
    }

    [Fact]
    public void Hearing_profile_matches_android_rule()
    {
        float[] l = [-60, -60, -60, -60, -50, -40, -30, -60];
        var c = HearingProfile.Compensation(l, l);
        Assert.Equal(0f, c[0]);
        Assert.Equal(5f, c[4]);
        Assert.Equal(6f, c[6]);
    }

    [Fact]
    public void Settings_store_round_trips_and_recovers_from_corruption()
    {
        var dir = Path.Combine(Path.GetTempPath(), "spatia-test-" + Guid.NewGuid());
        try
        {
            var store = new SettingsStore(dir, s => Native.Engine.Validate(s));
            var state = new AppState
            {
                PerType = new() { [OutputType.Wired] = new SoundSettings { BassDb = 4, Environment = Env.Cinema } },
                ManualOutput = OutputType.Usb,
            };
            store.Save(state);
            var back = store.Load();
            Assert.Equal(4f, back.PerType[OutputType.Wired].BassDb);
            Assert.Equal(Env.Cinema, back.PerType[OutputType.Wired].Environment);
            Assert.Equal(OutputType.Usb, back.ManualOutput);

            File.WriteAllText(Path.Combine(dir, "settings.json"), "{ broken");
            Assert.Equal(new AppState().RememberPerDevice, store.Load().RememberPerDevice);
            File.WriteAllText(Path.Combine(dir, "settings.json"), new string('x', SettingsStore.MaxBytes + 10));
            Assert.Empty(store.Load().PerType);

            File.WriteAllText(Path.Combine(dir, "settings.json"),
                """{"perType":{"WIRED":{"bassDb":99}},"perDevice":{"../evil":{"bassDb":1}}}""");
            var hostile = store.Load();
            Assert.Equal(12f, hostile.PerType[OutputType.Wired].BassDb);
            Assert.Empty(hostile.PerDevice);
        }
        finally { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
    }

    private sealed class FakeEngine : IEngineLogic
    {
        public OutputType ActiveOutput(IEnumerable<OutputType> connected, OutputType? manualOverride) =>
            manualOverride ?? (connected.Contains(OutputType.Bluetooth) ? OutputType.Bluetooth : OutputType.Speaker);
        public SoundSettings DefaultFor(OutputType output) => new() { BassDb = output == OutputType.Speaker ? 0 : 1 };
        public SoundSettings Validate(SoundSettings settings) => settings;
        public OutputSafety Safety(OutputType output) => new(3, -1, 0.6f, output != OutputType.Speaker);
    }

    [Fact]
    public void Profile_resolver_follows_device_then_type_then_default()
    {
        var resolver = new ProfileResolver(new FakeEngine());
        ConnectedOutput[] connected = [new(OutputType.Speaker, "Speakers", "aaaaaaaaaaaaaaaa", "1"), new(OutputType.Bluetooth, "Buds", "bbbbbbbbbbbbbbbb", "2")];
        var app = new AppState();

        var p = resolver.Resolve(app, connected);
        Assert.Equal(OutputType.Bluetooth, p.Output);
        Assert.Equal(1f, p.Settings.BassDb); // default for bluetooth
        Assert.Equal("bbbbbbbbbbbbbbbb", p.DeviceKey);

        app = ProfileResolver.Store(app, p, p.Settings with { BassDb = 5 });
        Assert.Equal(5f, resolver.Resolve(app, connected).Settings.BassDb);

        // The Windows default device wins over the generic preference.
        Assert.Equal(OutputType.Speaker, resolver.Resolve(app, connected, defaultDeviceId: "1").Output);
        // Manual override wins over everything.
        Assert.Equal(OutputType.Usb, resolver.Resolve(app with { ManualOutput = OutputType.Usb }, connected, "2").Output);
    }
}
