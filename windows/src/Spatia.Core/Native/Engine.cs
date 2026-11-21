using System.Runtime.InteropServices;
using System.Text;
using Spatia.Core.Models;

namespace Spatia.Core.Native;

/// <summary>Safe managed access to the shared engine's settings logic (presets, validation, safety rules, parsers).</summary>
public static unsafe class Engine
{
    /// <summary>C API version this app was built against.</summary>
    public const int ExpectedApiVersion = 2;

    public static int ApiVersion => NativeMethods.ApiVersion();

    public static bool IsCompatible => ApiVersion == ExpectedApiVersion;

    /// <summary>Clamps every value to its safe range; unknown or broken input falls back to defaults.</summary>
    public static SoundSettings Validate(SoundSettings s) =>
        Decode(CallWithJson(SpatiaJson.Serialize(s), NativeMethods.ValidateSettings)) ?? new SoundSettings();

    /// <summary>Validates raw JSON (e.g. from disk). Returns null if it can't be parsed at all.</summary>
    public static SoundSettings? ValidateJson(string json) => Decode(CallWithJson(json, NativeMethods.ValidateSettings));

    public static SoundSettings Preset(Mode mode, SoundSettings? baseSettings)
    {
        var json = baseSettings is null ? null : SpatiaJson.Serialize(baseSettings);
        string? result;
        if (json is null) result = TakeString(NativeMethods.Preset((int)mode, null));
        else result = CallWithJson(json, p => NativeMethods.Preset((int)mode, p));
        return Decode(result) ?? new SoundSettings();
    }

    public static SoundSettings DefaultFor(OutputType output) =>
        Decode(TakeString(NativeMethods.DefaultForOutput((int)output))) ?? new SoundSettings();

    public static OutputSafety Safety(OutputType output)
    {
        float* v = stackalloc float[4];
        if (NativeMethods.OutputSafety((int)output, v) != 0) throw new ArgumentOutOfRangeException(nameof(output));
        return new OutputSafety(v[0], v[1], v[2], v[3] != 0f);
    }

    public static float CapBoost(OutputType output, float requestedDb) => NativeMethods.CapBoost((int)output, requestedDb);

    public static OutputType ActiveOutput(IEnumerable<OutputType> connected, OutputType? manualOverride)
    {
        var mask = connected.Aggregate(0, (m, t) => m | (1 << (int)t));
        return (OutputType)NativeMethods.ActiveOutput(mask, manualOverride is null ? -1 : (int)manualOverride.Value);
    }

    public static AutoEqResult ParseAutoEq(ReadOnlySpan<byte> file)
    {
        string? json;
        fixed (byte* p = file) json = TakeString(NativeMethods.ParseAutoEq(p, file.Length));
        return (json is null ? null : SpatiaJson.Deserialize<AutoEqResult>(json))
            ?? new AutoEqResult(false, "Could not read the file", 0, [], []);
    }

    /// <summary>The built-in sample track, interleaved stereo float at <paramref name="sampleRate"/>.</summary>
    public static float[] RenderSampleTrack(int sampleRate)
    {
        int frames;
        var p = NativeMethods.SampleTrackRender(sampleRate, &frames);
        if (p == 0) throw new InvalidOperationException("Sample track could not be rendered.");
        try
        {
            var result = new float[frames * 2];
            new ReadOnlySpan<float>((void*)p, result.Length).CopyTo(result);
            return result;
        }
        finally { NativeMethods.Free(p); }
    }

    /// <summary>What Equalizer APO must do to approximate the chain for these settings (filters, preamp, matrices).</summary>
    public static Apo.ApoPlan ApoPlan(SoundSettings s, OutputType output, float attenuationDb)
    {
        var json = CallWithJson(SpatiaJson.Serialize(s), p => NativeMethods.ApoPlan(p, (int)output, attenuationDb));
        return (json is null ? null : SpatiaJson.Deserialize<Apo.ApoPlan>(json))
            ?? throw new ArgumentException("Settings rejected by engine.", nameof(s));
    }

    /// <summary>
    /// Renders the room part of the chain (spatial room, optional user impulse response, environment) as a 48 kHz
    /// 32-bit float stereo WAV for Equalizer APO's Convolution, plus the pre-gain (&lt;= 0 dB) that keeps the level.
    /// </summary>
    public static (byte[] Wav, float HeadroomDb) RenderApoImpulse(SoundSettings s, ReadOnlySpan<byte> userImpulseWav)
    {
        var json = Utf8Z(SpatiaJson.Serialize(s));
        int len;
        float headroom;
        nint p;
        fixed (byte* j = json)
        fixed (byte* w = userImpulseWav)
            p = NativeMethods.ApoRenderIr(j, userImpulseWav.IsEmpty ? null : w, userImpulseWav.Length, &len, &headroom);
        if (p == 0) throw new InvalidOperationException("Impulse response could not be rendered.");
        try { return (new ReadOnlySpan<byte>((void*)p, len).ToArray(), headroom); }
        finally { NativeMethods.Free(p); }
    }

    // ---- marshalling helpers ----

    private delegate nint JsonCall(byte* json);

    private static string? CallWithJson(string json, JsonCall call)
    {
        var bytes = Utf8Z(json);
        fixed (byte* p = bytes) return TakeString(call(p));
    }

    internal static byte[] Utf8Z(string s)
    {
        var bytes = new byte[Encoding.UTF8.GetByteCount(s) + 1];
        Encoding.UTF8.GetBytes(s, bytes);
        return bytes;
    }

    /// <summary>Copies a library-owned UTF-8 string and frees it.</summary>
    internal static string? TakeString(nint p)
    {
        if (p == 0) return null;
        try { return Marshal.PtrToStringUTF8(p); }
        finally { NativeMethods.Free(p); }
    }

    private static SoundSettings? Decode(string? json) => json is null ? null : SpatiaJson.Deserialize<SoundSettings>(json);
}
