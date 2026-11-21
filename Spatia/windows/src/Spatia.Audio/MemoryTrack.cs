using NAudio.Wave;

namespace Spatia.Audio;

/// <summary>An in-memory interleaved float track (used for Spatia's synthesised sample track).</summary>
public sealed class MemoryTrack(float[] samples, int sampleRate, int channels) : WaveStream, ISampleProvider
{
    private long _pos; // in samples

    public override WaveFormat WaveFormat { get; } = WaveFormat.CreateIeeeFloatWaveFormat(sampleRate, channels);
    public override long Length => samples.LongLength * 4;
    public override long Position
    {
        get => _pos * 4;
        set => _pos = Math.Clamp(value / 4 / channels * channels, 0, samples.LongLength);
    }

    public int Read(float[] buffer, int offset, int count)
    {
        var n = (int)Math.Min(count, samples.LongLength - _pos);
        if (n <= 0) return 0;
        Array.Copy(samples, _pos, buffer, offset, n);
        _pos += n;
        return n;
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var n = (int)Math.Min(count / 4, samples.LongLength - _pos);
        if (n <= 0) return 0;
        Buffer.BlockCopy(samples, (int)(_pos * 4), buffer, offset, n * 4);
        _pos += n;
        return n * 4;
    }
}
