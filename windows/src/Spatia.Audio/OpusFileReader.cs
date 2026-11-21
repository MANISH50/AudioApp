using Concentus;
using Concentus.Oggfile;
using NAudio.Wave;

namespace Spatia.Audio;

/// <summary>Ogg Opus decoder (Concentus, BSD-licensed managed port of libopus). Always 48 kHz stereo float.</summary>
public sealed class OpusFileReader : WaveStream, ISampleProvider
{
    private readonly Stream _file;
    private readonly OpusOggReadStream _ogg;
    private short[] _pending = [];
    private int _pendingPos;
    private long _positionSamples;

    public override WaveFormat WaveFormat { get; } = WaveFormat.CreateIeeeFloatWaveFormat(48000, 2);

    public OpusFileReader(string path)
    {
        _file = File.OpenRead(path);
        _ogg = new OpusOggReadStream(OpusCodecFactory.CreateDecoder(48000, 2), _file);
        if (!_ogg.HasNextPacket) throw new InvalidDataException("Not an Ogg Opus file.");
    }

    public override long Length => (long)(_ogg.TotalTime.TotalSeconds * 48000 * 2 * 4);

    public override long Position
    {
        get => _positionSamples * 4;
        set
        {
            var t = TimeSpan.FromSeconds(value / 4.0 / 2 / 48000);
            _ogg.SeekTo(t);
            _pending = []; _pendingPos = 0;
            _positionSamples = (long)(t.TotalSeconds * 48000 * 2);
        }
    }

    public int Read(float[] buffer, int offset, int count)
    {
        var written = 0;
        while (written < count)
        {
            if (_pendingPos >= _pending.Length)
            {
                if (!_ogg.HasNextPacket) break;
                _pending = _ogg.DecodeNextPacket() ?? [];
                _pendingPos = 0;
                continue;
            }
            buffer[offset + written++] = _pending[_pendingPos++] / 32768f;
        }
        _positionSamples += written;
        return written;
    }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var floats = new float[count / 4];
        var n = Read(floats, 0, floats.Length);
        Buffer.BlockCopy(floats, 0, buffer, offset, n * 4);
        return n * 4;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) _file.Dispose();
        base.Dispose(disposing);
    }
}
