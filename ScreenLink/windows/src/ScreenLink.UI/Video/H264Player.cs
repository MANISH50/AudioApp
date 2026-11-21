using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Channels;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Controls;
using ScreenLink.Core.Logic;
using ScreenLink.Core.Protocol;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Playback;

namespace ScreenLink.UI.Video;

/// <summary>
/// Shows the phone's H.264 stream with Windows' own decoder (Media Foundation, hardware-accelerated where available):
/// a live <see cref="MediaStreamSource"/> fed from the network, played in real-time mode. A new video configuration
/// (rotation, quality change) gets a fresh source. If frames pile up, they are dropped and a keyframe is requested.
/// </summary>
public sealed class H264Player : IDisposable
{
    private readonly MediaPlayerElement _element;
    private readonly DispatcherQueue _dq;
    private readonly Action _requestKeyframe;
    private Pipeline? _current;

    public H264Player(MediaPlayerElement element, DispatcherQueue dq, Action requestKeyframe)
    {
        _element = element;
        _dq = dq;
        _requestKeyframe = requestKeyframe;
    }

    /// <summary>Raised on the UI thread when a new picture size starts (width, height).</summary>
    public event Action<int, int>? VideoStarted;

    private sealed record Item(byte[] Data, TimeSpan Time, bool Key);

    private sealed class Pipeline(int width, int height, byte[] csd) : IDisposable
    {
        public readonly int Width = width, Height = height;
        public readonly byte[] Csd = csd;
        public readonly Channel<Item> Frames = Channel.CreateBounded<Item>(new BoundedChannelOptions(30) { SingleReader = true, SingleWriter = true });
        public readonly CancellationTokenSource Cts = new();
        public MediaStreamSource? Source;
        public MediaPlayer? Player;
        public long FirstPts = -1;
        public TimeSpan Last = TimeSpan.Zero;
        public bool GotKey;

        public bool Same(VideoConfigMessage c) => c.Width == Width && c.Height == Height && c.Csd.AsSpan().SequenceEqual(Csd);

        public void Dispose()
        {
            Cts.Cancel();
            Frames.Writer.TryComplete();
            if (Player is not null) { Player.Source = null; Player.Dispose(); }
            Cts.Dispose();
        }
    }

    /// <summary>New SPS/PPS and size. Any thread.</summary>
    public void OnConfig(VideoConfigMessage c)
    {
        var cur = Volatile.Read(ref _current);
        if (cur is not null && cur.Same(c)) { _requestKeyframe(); return; }
        var p = new Pipeline(c.Width, c.Height, c.Csd);
        var old = Interlocked.Exchange(ref _current, p);
        _dq.TryEnqueue(() =>
        {
            old?.Dispose();
            if (Volatile.Read(ref _current) != p) return;
            try { Start(p); }
            catch (Exception e) when (e is COMException or InvalidOperationException or ArgumentException)
            {
                AppLog.Error("video", e);
            }
        });
    }

    /// <summary>One encoded frame. Any thread (the network reader).</summary>
    public void OnFrame(VideoFrameMessage f)
    {
        var p = Volatile.Read(ref _current);
        if (p is null || p.Source is null) return;
        if (!p.GotKey && !f.KeyFrame) return;
        if (p.FirstPts < 0) p.FirstPts = f.PtsMicros;
        var t = TimeSpan.FromTicks(Math.Max(0, f.PtsMicros - p.FirstPts) * 10);
        if (t <= p.Last && p.GotKey) t = p.Last + TimeSpan.FromTicks(1);
        byte[] data;
        if (f.KeyFrame)
        {
            // Parameter sets in front of every keyframe, so the decoder can (re)start at any keyframe.
            data = new byte[p.Csd.Length + f.Data.Count];
            p.Csd.CopyTo(data, 0);
            f.Data.AsSpan().CopyTo(data.AsSpan(p.Csd.Length));
        }
        else data = f.Data.ToArray();
        if (p.Frames.Writer.TryWrite(new Item(data, t, f.KeyFrame)))
        {
            p.GotKey = true;
            p.Last = t;
        }
        else
        {
            // Decoder is behind: drop what's queued and restart from the next keyframe.
            while (p.Frames.Reader.TryRead(out _)) { }
            p.GotKey = false;
            _requestKeyframe();
        }
    }

    /// <summary>The phone stopped casting or disconnected.</summary>
    public void Stop()
    {
        var old = Interlocked.Exchange(ref _current, null);
        if (old is not null) _dq.TryEnqueue(() => { old.Dispose(); _element.SetMediaPlayer(null); });
    }

    private void Start(Pipeline p)
    {
        var props = VideoEncodingProperties.CreateH264();
        props.Width = (uint)p.Width;
        props.Height = (uint)p.Height;
        var source = new MediaStreamSource(new VideoStreamDescriptor(props))
        {
            BufferTime = TimeSpan.Zero,
            CanSeek = false,
            IsLive = true,
        };
        source.SampleRequested += (s, a) => OnSampleRequested(p, a);
        var player = new MediaPlayer { RealTimePlayback = true, IsMuted = true, AutoPlay = true };
        player.MediaFailed += (_, e) =>
        {
            AppLog.Error("video", $"playback failed: {e.Error} 0x{e.ExtendedErrorCode?.HResult:X8}");
            p.GotKey = false;
            _requestKeyframe();
        };
        p.Source = source;
        p.Player = player;
        player.Source = MediaSource.CreateFromMediaStreamSource(source);
        _element.SetMediaPlayer(player);
        player.Play();
        AppLog.Info("video", $"stream {p.Width}x{p.Height}");
        VideoStarted?.Invoke(p.Width, p.Height);
        _requestKeyframe();
    }

    private static async void OnSampleRequested(Pipeline p, MediaStreamSourceSampleRequestedEventArgs args)
    {
        var request = args.Request;
        var deferral = request.GetDeferral();
        try
        {
            var item = await p.Frames.Reader.ReadAsync(p.Cts.Token).ConfigureAwait(false);
            var sample = MediaStreamSample.CreateFromBuffer(item.Data.AsBuffer(), item.Time);
            sample.KeyFrame = item.Key;
            request.Sample = sample;
        }
        catch (Exception e) when (e is OperationCanceledException or ChannelClosedException or ObjectDisposedException)
        {
            request.Sample = null; // end of this stream
        }
        catch (COMException e)
        {
            AppLog.Error("video", e);
            request.Sample = null;
        }
        finally
        {
            try { deferral.Complete(); } catch (COMException) { /* source already closed */ }
        }
    }

    public void Dispose()
    {
        Interlocked.Exchange(ref _current, null)?.Dispose();
    }
}
