using NAudio.Wave;
using Spatia.Core.Native;

namespace Spatia.Audio;

/// <summary>
/// Runs decoded audio through the shared engine. Accepts 1, 2, 6 or 8 channels and always outputs stereo float at the
/// source rate. A gain stage after the engine is used only for the sleep-timer fade.
/// </summary>
public sealed class SpatiaSampleProvider : ISampleProvider, IDisposable
{
    private readonly ISampleProvider _source;
    private float[] _inBuf = [];

    public DspChain Chain { get; }
    public WaveFormat WaveFormat { get; }
    /// <summary>0..1, applied after the limiter (only ever reduces level).</summary>
    public float FadeGain { get; set; } = 1f;

    public SpatiaSampleProvider(ISampleProvider source)
    {
        _source = source;
        var f = source.WaveFormat;
        if (!DspChain.Supports(f.SampleRate, f.Channels))
            throw new NotSupportedException($"{f.Channels} channels at {f.SampleRate} Hz is not supported.");
        Chain = new DspChain(f.SampleRate, f.Channels);
        WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(f.SampleRate, 2);
    }

    public int Read(float[] buffer, int offset, int count)
    {
        var frames = count / 2;
        var inCh = _source.WaveFormat.Channels;
        if (_inBuf.Length < frames * inCh) _inBuf = new float[frames * inCh];
        var read = _source.Read(_inBuf, 0, frames * inCh);
        var gotFrames = read / inCh;
        if (gotFrames == 0) return 0;
        Chain.Process(_inBuf.AsSpan(0, gotFrames * inCh), buffer.AsSpan(offset, gotFrames * 2), gotFrames);
        var g = Math.Clamp(FadeGain, 0f, 1f);
        if (g < 1f) for (var i = 0; i < gotFrames * 2; i++) buffer[offset + i] *= g;
        return gotFrames * 2;
    }

    public void Dispose() => Chain.Dispose();
}
