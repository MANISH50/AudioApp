using System.Text.RegularExpressions;
using Spatia.Core.Models;

namespace Spatia.Core.Settings;

/// <summary>Everything Spatia for Windows stores. Kept only in %LOCALAPPDATA%\Spatia; never transmitted.</summary>
public sealed partial record AppState
{
    public Dictionary<OutputType, SoundSettings> PerType { get; init; } = [];
    public Dictionary<string, SoundSettings> PerDevice { get; init; } = [];
    public bool RememberPerDevice { get; init; } = true;
    public OutputType? ManualOutput { get; init; }
    public SoundSettings? CustomPreset { get; init; }
    /// <summary>WASAPI exclusive mode: bit-perfect to the device, all processing bypassed.</summary>
    public bool ExclusiveMode { get; init; }
    public bool DoseTrackingEnabled { get; init; }
    public long DoseWeek { get; init; }
    public double DoseFraction { get; init; }
    public long VolumeWarningAcknowledgedAtUnixMs { get; init; }

    public const int MaxDevices = 32;

    [GeneratedRegex("^[0-9a-f]{16}$")]
    private static partial Regex DeviceKeyRx();

    /// <summary>Re-validates everything loaded from disk through the shared engine.</summary>
    public AppState Validated(Func<SoundSettings, SoundSettings> validate) => this with
    {
        PerType = PerType.Where(kv => Enum.IsDefined(kv.Key))
            .ToDictionary(kv => kv.Key, kv => validate(kv.Value)),
        PerDevice = PerDevice.Where(kv => DeviceKeyRx().IsMatch(kv.Key)).Take(MaxDevices)
            .ToDictionary(kv => kv.Key, kv => validate(kv.Value)),
        ManualOutput = ManualOutput is { } m && Enum.IsDefined(m) ? m : null,
        CustomPreset = CustomPreset is null ? null : validate(CustomPreset),
        DoseFraction = double.IsFinite(DoseFraction) ? Math.Clamp(DoseFraction, 0, 100) : 0,
    };
}
