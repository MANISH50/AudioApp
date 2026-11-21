using Spatia.Core.Models;

namespace Spatia.Core.Native;

/// <summary>
/// The shared processing chain (EQ, environments, spatial, dynamics, limiter). Input: interleaved float PCM with
/// 1, 2, 6 or 8 channels. Output: always interleaved stereo. <see cref="Process"/> must be called from one audio thread;
/// the setters may be called from any thread (the engine hands them over safely).
/// </summary>
public sealed unsafe class DspChain : IDisposable
{
    private nint _handle;
    private readonly object _lifetime = new();

    public int SampleRate { get; }
    public int Channels { get; }

    public DspChain(int sampleRate, int channels)
    {
        _handle = NativeMethods.ChainCreate(sampleRate, channels);
        if (_handle == 0) throw new NotSupportedException($"Unsupported format: {sampleRate} Hz, {channels} channels");
        SampleRate = sampleRate;
        Channels = channels;
    }

    public static bool Supports(int sampleRate, int channels) =>
        sampleRate is >= 8000 and <= 384000 && channels is 1 or 2 or 6 or 8;

    public void SetSettings(SoundSettings settings, OutputType output, float volumeAttenuationDb = 0f)
    {
        var bytes = Engine.Utf8Z(SpatiaJson.Serialize(settings));
        lock (_lifetime)
        {
            ThrowIfDisposed();
            fixed (byte* p = bytes)
            {
                var rc = NativeMethods.ChainSetSettings(_handle, p, (int)output, volumeAttenuationDb);
                if (rc != 0) throw new ArgumentException($"Settings rejected by engine (code {rc}).");
            }
        }
    }

    public void SetHeadYaw(double yawRad) { lock (_lifetime) if (_handle != 0) NativeMethods.ChainSetHeadYaw(_handle, yawRad); }

    /// <summary>Loads a WAV impulse response for the room effect (empty clears). Returns false if the WAV is invalid.</summary>
    public bool SetImpulse(ReadOnlySpan<byte> wav)
    {
        lock (_lifetime)
        {
            ThrowIfDisposed();
            fixed (byte* p = wav) return NativeMethods.ChainSetImpulse(_handle, p, wav.Length) == 0;
        }
    }

    public void Process(ReadOnlySpan<float> input, Span<float> output, int frames)
    {
        if (input.Length < frames * Channels || output.Length < frames * 2)
            throw new ArgumentException("Buffers are too small for the requested frame count.");
        lock (_lifetime)
        {
            ThrowIfDisposed();
            fixed (float* i = input)
            fixed (float* o = output)
            {
                if (NativeMethods.ChainProcess(_handle, i, o, frames) != 0)
                    throw new InvalidOperationException("Engine rejected the audio block.");
            }
        }
    }

    public (float PeakDb, float RmsDb, float LimiterDb, float NormalizerDb) Meters()
    {
        float* v = stackalloc float[4];
        lock (_lifetime)
        {
            if (_handle == 0 || NativeMethods.ChainMeters(_handle, v) != 0) return (-120, -120, 0, 0);
        }
        return (v[0], v[1], v[2], v[3]);
    }

    public float[] Spectrum(int bands = 24)
    {
        var result = new float[bands];
        lock (_lifetime)
        {
            if (_handle == 0) return result;
            fixed (float* p = result)
            {
                if (NativeMethods.ChainSpectrum(_handle, p, bands) != 0) Array.Fill(result, -120f);
            }
        }
        return result;
    }

    private void ThrowIfDisposed() => ObjectDisposedException.ThrowIf(_handle == 0, this);

    public void Dispose()
    {
        lock (_lifetime)
        {
            if (_handle != 0) { NativeMethods.ChainDestroy(_handle); _handle = 0; }
        }
    }
}
