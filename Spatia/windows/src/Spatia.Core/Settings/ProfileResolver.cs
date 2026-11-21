using Spatia.Core.Models;

namespace Spatia.Core.Settings;

public sealed record ConnectedOutput(OutputType Type, string Name, string Key, string Id);

/// <summary>The settings that apply right now, and where edits to them are stored.</summary>
public sealed record EffectiveProfile(OutputType Output, string? DeviceKey, SoundSettings Settings, OutputSafety Safety);

/// <summary>
/// Same rules as the Android app: the active output is chosen by the engine (manual override, else the most personal
/// connected output); a remembered per-device profile wins over the per-type profile, which wins over the default.
/// </summary>
public sealed class ProfileResolver(IEngineLogic engine)
{
    public EffectiveProfile Resolve(AppState app, IReadOnlyList<ConnectedOutput> connected, string? defaultDeviceId = null)
    {
        // On Windows the device that is actually playing is the default render device; prefer it when known.
        var fromDefault = connected.FirstOrDefault(c => c.Id == defaultDeviceId);
        var output = app.ManualOutput
            ?? fromDefault?.Type
            ?? engine.ActiveOutput(connected.Select(c => c.Type), null);
        var device = fromDefault?.Type == output ? fromDefault : connected.FirstOrDefault(c => c.Type == output);
        var key = app.RememberPerDevice && output != OutputType.Speaker ? device?.Key : null;
        var settings = (key is not null && app.PerDevice.TryGetValue(key, out var d)) ? d
            : app.PerType.TryGetValue(output, out var t) ? t
            : engine.DefaultFor(output);
        return new EffectiveProfile(output, key, engine.Validate(settings), engine.Safety(output));
    }

    /// <summary>Stores edited settings where they came from (per device if remembered, else per output type).</summary>
    public static AppState Store(AppState app, EffectiveProfile where, SoundSettings edited)
    {
        var next = edited.Mode == Mode.Custom ? app with { CustomPreset = edited } : app;
        if (where.DeviceKey is { } key)
            return next with { PerDevice = new Dictionary<string, SoundSettings>(next.PerDevice) { [key] = edited } };
        return next with { PerType = new Dictionary<OutputType, SoundSettings>(next.PerType) { [where.Output] = edited } };
    }
}

/// <summary>The engine calls the resolver needs; an interface so logic can be unit-tested with or without the DLL.</summary>
public interface IEngineLogic
{
    OutputType ActiveOutput(IEnumerable<OutputType> connected, OutputType? manualOverride);
    SoundSettings DefaultFor(OutputType output);
    SoundSettings Validate(SoundSettings settings);
    OutputSafety Safety(OutputType output);
}

public sealed class NativeEngineLogic : IEngineLogic
{
    public OutputType ActiveOutput(IEnumerable<OutputType> connected, OutputType? manualOverride) =>
        Native.Engine.ActiveOutput(connected, manualOverride);
    public SoundSettings DefaultFor(OutputType output) => Native.Engine.DefaultFor(output);
    public SoundSettings Validate(SoundSettings settings) => Native.Engine.Validate(settings);
    public OutputSafety Safety(OutputType output) => Native.Engine.Safety(output);
}
