using NAudio.Wave;

namespace Spatia.Audio;

/// <summary>Wraps a stream and restarts it at the end while <see cref="Loop"/> is on (used for the sample track).</summary>
public sealed class LoopStream(WaveStream source) : WaveStream
{
    public bool Loop { get; set; }
    public override WaveFormat WaveFormat => source.WaveFormat;
    public override long Length => source.Length;
    public override long Position { get => source.Position; set => source.Position = value; }

    public override int Read(byte[] buffer, int offset, int count)
    {
        var total = 0;
        while (total < count)
        {
            var n = source.Read(buffer, offset + total, count - total);
            if (n == 0)
            {
                if (!Loop || source.Position == 0) break;
                source.Position = 0;
            }
            total += n;
        }
        return total;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) source.Dispose();
        base.Dispose(disposing);
    }
}
