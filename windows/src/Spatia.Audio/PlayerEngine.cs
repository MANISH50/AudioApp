using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;
using Spatia.Core.Logic;
using Spatia.Core.Native;

namespace Spatia.Audio;

/// <summary>
/// The built-in player: decode (Media Foundation, or Concentus for Opus) → shared engine → WASAPI output.
/// In exclusive mode the decoded audio goes to the device untouched (bit-perfect) and the engine is bypassed.
/// Events are raised on audio/COM threads; the UI must marshal them.
/// </summary>
public sealed class PlayerEngine : IDisposable
{
    private WaveStream? _reader;
    private LoopStream? _loop;
    private SpatiaSampleProvider? _dsp;
    private WasapiOut? _out;
    private readonly object _gate = new();

    public event Action? StateChanged;
    public event Action<PlayerError>? Failed;
    /// <summary>A new engine chain was created (new file or format): push current settings to it.</summary>
    public event Action<DspChain>? ChainCreated;

    public string? Title { get; private set; }
    public bool IsSample { get; private set; }
    public bool ExclusiveMode { get; private set; }
    public bool IsPlaying => _out?.PlaybackState == PlaybackState.Playing;
    public TimeSpan Position => _reader?.CurrentTime ?? TimeSpan.Zero;
    public TimeSpan Duration => _reader?.TotalTime ?? TimeSpan.Zero;
    public DspChain? Chain => _dsp?.Chain;
    public float FadeGain { get => _dsp?.FadeGain ?? 1f; set { if (_dsp is { } d) d.FadeGain = value; } }

    /// <summary>Opens a file after checking the format. Returns the verdict; plays only when supported.</summary>
    public FormatVerdict Open(string path)
    {
        var verdict = FormatSupport.Classify(path);
        if (verdict is not (FormatVerdict.Supported or FormatVerdict.OpusDecoder)) return verdict;
        WaveStream reader;
        try
        {
            reader = verdict == FormatVerdict.OpusDecoder ? new OpusFileReader(path) : new MediaFoundationReader(path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Failed?.Invoke(PlayerError.FileAccess); return verdict;
        }
        catch (Exception)
        {
            Failed?.Invoke(PlayerError.Decoder); return verdict;
        }
        Load(reader, Path.GetFileNameWithoutExtension(path), loop: false, sample: false);
        return verdict;
    }

    /// <summary>Plays Spatia's synthesised sample track on loop.</summary>
    public void OpenSample(float[] stereo, int sampleRate, string title) =>
        Load(new MemoryTrack(stereo, sampleRate, 2), title, loop: true, sample: true);

    private void Load(WaveStream reader, string title, bool loop, bool sample)
    {
        lock (_gate)
        {
            TearDown();
            _reader = reader;
            _loop = new LoopStream(reader) { Loop = loop };
            Title = title;
            IsSample = sample;
            if (!StartOutput(startPlaying: true)) return;
        }
        StateChanged?.Invoke();
    }

    /// <summary>Switches between shared (processed) and exclusive (bit-perfect, unprocessed) output.</summary>
    public void SetExclusiveMode(bool exclusive)
    {
        lock (_gate)
        {
            if (ExclusiveMode == exclusive) return;
            ExclusiveMode = exclusive;
            if (_loop is not null) RestartOutput();
        }
        StateChanged?.Invoke();
    }

    /// <summary>Re-opens the output on the current default device (call when Windows' default device changes).</summary>
    public void RestartOutput()
    {
        lock (_gate)
        {
            if (_loop is null) return;
            var wasPlaying = IsPlaying;
            var pos = _reader!.Position;
            DisposeOutput();
            _reader.Position = pos;
            StartOutput(wasPlaying);
        }
        StateChanged?.Invoke();
    }

    private bool StartOutput(bool startPlaying)
    {
        MMDevice device;
        try { device = new MMDeviceEnumerator().GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); }
        catch (Exception) { Failed?.Invoke(PlayerError.Device); return false; }

        if (ExclusiveMode)
        {
            try
            {
                _out = new WasapiOut(device, AudioClientShareMode.Exclusive, true, 50);
                _out.Init(_loop);
            }
            catch (Exception)
            {
                // The device doesn't accept this file's exact format exclusively: fall back to processed output.
                _out?.Dispose(); _out = null;
                ExclusiveMode = false;
                Failed?.Invoke(PlayerError.ExclusiveRefused);
            }
        }
        if (_out is null)
        {
            ISampleProvider sp = _loop!.ToSampleProvider();
            if (!DspChain.Supports(sp.WaveFormat.SampleRate, sp.WaveFormat.Channels))
            {
                // e.g. 3- or 4-channel files: fold to stereo first so they still play (processed).
                sp = sp.WaveFormat.Channels > 2 ? new StereoFold(sp) : sp;
            }
            _dsp = new SpatiaSampleProvider(sp);
            ChainCreated?.Invoke(_dsp.Chain);
            _out = new WasapiOut(device, AudioClientShareMode.Shared, true, 100);
            _out.Init(new SampleToWaveProvider(_dsp));
        }
        _out.PlaybackStopped += OnStopped;
        if (startPlaying) _out.Play();
        return true;
    }

    private void OnStopped(object? sender, StoppedEventArgs e)
    {
        if (e.Exception is not null) Failed?.Invoke(PlayerError.Device);
        StateChanged?.Invoke();
    }

    public void PlayPause()
    {
        lock (_gate)
        {
            if (_out is null) return;
            if (IsPlaying) _out.Pause();
            else
            {
                if (_reader is { } r && r.Position >= r.Length && !(_loop?.Loop ?? false)) r.Position = 0;
                _out.Play();
            }
        }
        StateChanged?.Invoke();
    }

    public void Pause() { lock (_gate) _out?.Pause(); StateChanged?.Invoke(); }

    public void Seek(TimeSpan t)
    {
        lock (_gate)
        {
            if (_reader is null) return;
            var clamped = t < TimeSpan.Zero ? TimeSpan.Zero : t > _reader.TotalTime ? _reader.TotalTime : t;
            _reader.CurrentTime = clamped;
        }
        StateChanged?.Invoke();
    }

    private void DisposeOutput()
    {
        if (_out is not null) { _out.PlaybackStopped -= OnStopped; _out.Stop(); _out.Dispose(); _out = null; }
        _dsp?.Dispose(); _dsp = null;
    }

    private void TearDown()
    {
        DisposeOutput();
        _loop?.Dispose(); _loop = null; _reader = null;
        Title = null; IsSample = false;
    }

    public void Dispose() { lock (_gate) TearDown(); }

    /// <summary>Simple fold of any channel count to stereo (odd channels left, even right).</summary>
    private sealed class StereoFold(ISampleProvider src) : ISampleProvider
    {
        private float[] _buf = [];
        public WaveFormat WaveFormat { get; } = WaveFormat.CreateIeeeFloatWaveFormat(src.WaveFormat.SampleRate, 2);
        public int Read(float[] buffer, int offset, int count)
        {
            var ch = src.WaveFormat.Channels;
            var frames = count / 2;
            if (_buf.Length < frames * ch) _buf = new float[frames * ch];
            var got = src.Read(_buf, 0, frames * ch) / ch;
            for (var f = 0; f < got; f++)
            {
                float l = 0, r = 0;
                for (var c = 0; c < ch; c++) { if (c % 2 == 0) l += _buf[f * ch + c]; else r += _buf[f * ch + c]; }
                buffer[offset + 2 * f] = l / (ch / 2f);
                buffer[offset + 2 * f + 1] = r / (ch / 2f);
            }
            return got * 2;
        }
    }
}
