using System.Runtime.InteropServices;

namespace Spatia.Core.Native;

/// <summary>
/// P/Invoke declarations for the shared Kotlin engine (spatia_dsp.dll on Windows, libspatia_dsp.so on Linux).
/// Signatures mirror android/core-dsp/src/nativeMain/kotlin/io/spatia/dsp/capi/CApi.kt.
/// </summary>
internal static unsafe partial class NativeMethods
{
    private const string Lib = "spatia_dsp";

    [LibraryImport(Lib, EntryPoint = "spatia_api_version")] internal static partial int ApiVersion();
    [LibraryImport(Lib, EntryPoint = "spatia_free")] internal static partial void Free(nint p);

    [LibraryImport(Lib, EntryPoint = "spatia_chain_create")] internal static partial nint ChainCreate(int sampleRate, int channels);
    [LibraryImport(Lib, EntryPoint = "spatia_chain_destroy")] internal static partial void ChainDestroy(nint h);
    [LibraryImport(Lib, EntryPoint = "spatia_chain_set_settings")] internal static partial int ChainSetSettings(nint h, byte* json, int output, float attenuationDb);
    [LibraryImport(Lib, EntryPoint = "spatia_chain_process")] internal static partial int ChainProcess(nint h, float* input, float* output, int frames);
    [LibraryImport(Lib, EntryPoint = "spatia_chain_set_head_yaw")] internal static partial void ChainSetHeadYaw(nint h, double yawRad);
    [LibraryImport(Lib, EntryPoint = "spatia_chain_meters")] internal static partial int ChainMeters(nint h, float* out4);
    [LibraryImport(Lib, EntryPoint = "spatia_chain_spectrum")] internal static partial int ChainSpectrum(nint h, float* output, int bands);
    [LibraryImport(Lib, EntryPoint = "spatia_chain_set_impulse")] internal static partial int ChainSetImpulse(nint h, byte* wav, int len);

    [LibraryImport(Lib, EntryPoint = "spatia_validate_settings")] internal static partial nint ValidateSettings(byte* json);
    [LibraryImport(Lib, EntryPoint = "spatia_preset")] internal static partial nint Preset(int mode, byte* baseJson);
    [LibraryImport(Lib, EntryPoint = "spatia_default_for_output")] internal static partial nint DefaultForOutput(int output);
    [LibraryImport(Lib, EntryPoint = "spatia_output_safety")] internal static partial int OutputSafety(int output, float* out4);
    [LibraryImport(Lib, EntryPoint = "spatia_cap_boost")] internal static partial float CapBoost(int output, float requestedDb);
    [LibraryImport(Lib, EntryPoint = "spatia_active_output")] internal static partial int ActiveOutput(int connectedMask, int overrideType);
    [LibraryImport(Lib, EntryPoint = "spatia_parse_autoeq")] internal static partial nint ParseAutoEq(byte* bytes, int len);
    [LibraryImport(Lib, EntryPoint = "spatia_sample_track_render")] internal static partial nint SampleTrackRender(int sampleRate, int* framesOut);

    [LibraryImport(Lib, EntryPoint = "spatia_apo_plan")] internal static partial nint ApoPlan(byte* json, int output, float attenuationDb);
    [LibraryImport(Lib, EntryPoint = "spatia_apo_render_ir")] internal static partial nint ApoRenderIr(byte* json, byte* wav, int wavLen, int* lenOut, float* headroomDbOut);
}
