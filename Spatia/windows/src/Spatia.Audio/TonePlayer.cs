using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace Spatia.Audio;

/// <summary>
/// Plays short sounds straight to Windows' default output (shared mode, no Spatia processing): faded sine tones for
/// the hearing check, and the "Test sound" clip. Because the clip is unprocessed by Spatia, what you hear is exactly
/// what Equalizer APO does to every app.
/// </summary>
public sealed class TonePlayer : IDisposable
{
    private WasapiOut? _out;

    public bool IsPlaying => _out?.PlaybackState == PlaybackState.Playing;

    public void Play(float freqHz, float levelDbfs, bool leftEar)
    {
        const int sr = 48000;
        var amp = MathF.Pow(10, Math.Clamp(levelDbfs, -90f, -10f) / 20f);
        var frames = sr; var fade = sr / 20;
        var data = new float[frames * 2];
        for (var i = 0; i < frames; i++)
        {
            var env = Math.Min(1f, Math.Min(i / (float)fade, (frames - i) / (float)fade));
            var v = MathF.Sin(2 * MathF.PI * freqHz * i / sr) * amp * env;
            data[2 * i + (leftEar ? 0 : 1)] = v;
        }
        PlayClip(data, sr);
    }

    /// <summary>Plays interleaved stereo float samples once.</summary>
    public void PlayClip(float[] interleavedStereo, int sampleRate)
    {
        Stop();
        using var enumerator = new MMDeviceEnumerator();
        var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        _out = new WasapiOut(device, AudioClientShareMode.Shared, true, 100);
        _out.Init(new MemoryTrack(interleavedStereo, sampleRate, 2));
        _out.Play();
    }

    public void Stop() { _out?.Stop(); _out?.Dispose(); _out = null; }

    public void Dispose() => Stop();
}
