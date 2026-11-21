using System.Globalization;
using System.Text.RegularExpressions;
using Spatia.Core.Logic;
using Spatia.Core.Models;
using Xunit;

namespace Spatia.Core.Tests;

/// <summary>Guards localisation: every string the UI asks for must exist in the English .resw.</summary>
public partial class StringsTests
{
    private static readonly string Root = FindRepoRoot();
    private static readonly string AppDir = Path.Combine(Root, "windows", "src", "Spatia.App");

    private static string FindRepoRoot()
    {
        var d = new DirectoryInfo(AppContext.BaseDirectory);
        while (d is not null && !Directory.Exists(Path.Combine(d.FullName, "windows", "src"))) d = d.Parent;
        return d?.FullName ?? throw new DirectoryNotFoundException("repo root");
    }

    private static StringTable English()
    {
        using var f = File.OpenRead(Path.Combine(AppDir, "Strings", "en-US", "Resources.resw"));
        return new StringTable(StringTable.Parse(f));
    }

    [GeneratedRegex(@"(?:\bs|App\.S)(?:\.Format)?[\[\(]""([A-Za-z_]+)""")]
    private static partial Regex KeyUse();

    [Fact]
    public void Every_key_used_in_code_exists()
    {
        var table = English();
        var missing = Directory.EnumerateFiles(AppDir, "*.cs", SearchOption.AllDirectories)
            .SelectMany(f => KeyUse().Matches(File.ReadAllText(f)).Select(m => m.Groups[1].Value))
            .Where(k => !k.EndsWith('_'))
            .Distinct()
            .Where(k => !table.Keys.Contains(k))
            .ToList();
        Assert.Empty(missing);
    }

    [Fact]
    public void Every_enum_driven_key_exists()
    {
        var keys = English().Keys;
        var expected = Enum.GetValues<Mode>().Select(m => "Mode_" + m)
            .Concat(Enum.GetValues<Models.Environment>().SelectMany(e => new[] { "Env_" + e, "EnvDesc_" + e }))
            .Concat(Enum.GetValues<OutputType>().Select(o => "Output_" + o))
            .Concat(Enum.GetValues<FormatVerdict>().Select(v => "Format_" + v))
            .Concat(Enum.GetValues<PlayerError>().Select(e => "PlayerError_" + e))
            .Concat(new[] { "Status_Available", "Status_Limited", "Status_Unavailable" })
            .Concat(new[] { "Pause", "Play", "SampleGroove", "SampleVoice", "SampleHits", "SampleSweep", "IrLoaded", "IrFailed",
                "OutputDetected", "OutputManual", "HearingLeft", "HearingRight", "HearingNeedsHeadphones",
                "VolumeWarningHeadphones", "VolumeWarningSpeaker" });
        var missing = expected.Where(k => !keys.Contains(k)).ToList();
        Assert.True(missing.Count == 0, "Missing: " + string.Join(", ", missing));
    }

    [Fact]
    public void Placeholders_format_and_missing_keys_are_visible()
    {
        var t = English();
        Assert.Equal("Left ear, 1000 Hz", t.Format("HearingLeft", 1000));
        Assert.Equal("[NoSuchKey]", t["NoSuchKey"]);
    }

    [Fact]
    public void Resw_parser_rejects_dtd()
    {
        var evil = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x "boom">]><root><data name="a"><value>&x;</value></data></root>""";
        using var s = new MemoryStream(System.Text.Encoding.UTF8.GetBytes(evil));
        Assert.ThrowsAny<System.Xml.XmlException>(() => StringTable.Parse(s));
    }

    [Fact]
    public void Culture_fallback_uses_english_for_missing_translations()
    {
        var t = StringTable.Load(name => name == "en-US" ? File.OpenRead(Path.Combine(AppDir, "Strings", "en-US", "Resources.resw")) : null,
            new CultureInfo("de-DE"));
        Assert.Equal("Hall", t["Env_Hall"]);
    }

    [Fact]
    public void Dose_matches_android_formula()
    {
        Assert.Equal(1.0, DoseTracker.DoseFraction(80, 40 * 3600), 9);
        Assert.Equal(1.0, DoseTracker.DoseFraction(83, 20 * 3600), 9);
        Assert.Equal(0.0, DoseTracker.DoseFraction(double.NaN, 100));
    }
}
