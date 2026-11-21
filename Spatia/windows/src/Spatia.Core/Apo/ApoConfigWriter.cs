using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Spatia.Core.Models;
using Spatia.Core.Native;

namespace Spatia.Core.Apo;

/// <summary>One write request: the effective settings for the active output and what they depend on.</summary>
public sealed record ApoRequest(SoundSettings Settings, OutputType Output, float AttenuationDb, byte[]? UserImpulse = null, string? Label = null);

/// <summary>
/// Turns settings into config\Spatia\spatia.txt (+ env_&lt;hash&gt;.wav for rooms) for Equalizer APO. Changes are
/// debounced (~150 ms) so dragging a slider doesn't rewrite files on every step; files are replaced atomically
/// (Equalizer APO watches them and reloads on change); stale impulse responses are deleted.
/// </summary>
public sealed class ApoConfigWriter : IDisposable
{
    public const int DefaultDebounceMs = 150;
    public const string ImpulsePrefix = "env_";

    private readonly string _dir;
    private readonly Func<ApoRequest, ApoPlan> _plan;
    private readonly Func<SoundSettings, byte[]?, (byte[] Wav, float HeadroomDb)> _renderImpulse;
    private readonly Timer _timer;
    private readonly int _debounceMs;
    private readonly object _gate = new();
    private readonly object _writeGate = new();
    private ApoRequest? _pending;
    private string? _irKey;
    private float _irHeadroom;
    private bool _disposed;

    /// <summary>Raised (on a worker thread) after spatia.txt was written or confirmed up to date.</summary>
    public event Action<ApoPlan>? Written;
    /// <summary>Raised (on a worker thread) when writing failed.</summary>
    public event Action<Exception>? Failed;

    public ApoPlan? LastPlan { get; private set; }

    public ApoConfigWriter(string spatiaDir, int debounceMs = DefaultDebounceMs,
        Func<ApoRequest, ApoPlan>? plan = null, Func<SoundSettings, byte[]?, (byte[] Wav, float HeadroomDb)>? renderImpulse = null)
    {
        _dir = spatiaDir;
        _debounceMs = debounceMs;
        _plan = plan ?? (r => Engine.ApoPlan(r.Settings, r.Output, r.AttenuationDb));
        _renderImpulse = renderImpulse ?? ((s, ir) => Engine.RenderApoImpulse(s, ir));
        _timer = new Timer(_ => Flush());
    }

    public string ConfigPath => Path.Combine(_dir, ApoInstaller.SpatiaConfigFile);

    /// <summary>Queues a write; only the latest request within the debounce window is written.</summary>
    public void Schedule(ApoRequest request)
    {
        lock (_gate)
        {
            if (_disposed) return;
            _pending = request;
            _timer.Change(_debounceMs, Timeout.Infinite);
        }
    }

    /// <summary>Writes the pending request now (if any).</summary>
    public void Flush()
    {
        ApoRequest? r;
        lock (_gate) { r = _pending; _pending = null; }
        if (r is null) return;
        try { Written?.Invoke(Write(r)); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidOperationException or ArgumentException)
        {
            Failed?.Invoke(e);
        }
    }

    /// <summary>Writes synchronously and returns the plan that was written.</summary>
    public ApoPlan Write(ApoRequest r)
    {
        lock (_writeGate)
        {
            Directory.CreateDirectory(_dir);
            var plan = _plan(r);
            var userIr = r.Settings.ConvolutionEnabled && r.UserImpulse is { Length: > 0 } u ? u : null;
            string? irFile = null;
            if (!plan.Bypass && (plan.NeedsImpulse || userIr is not null))
            {
                var key = ImpulseKey(r.Settings, userIr);
                irFile = ImpulsePrefix + key + ".wav";
                var irPath = Path.Combine(_dir, irFile);
                if (_irKey != key || !File.Exists(irPath))
                {
                    var (wav, headroom) = _renderImpulse(r.Settings, userIr);
                    ApoInstaller.WriteAtomic(irPath, wav);
                    _irKey = key;
                    _irHeadroom = headroom;
                }
            }
            var text = ApoConfigText.Build(plan, irFile, irFile is null ? 0f : _irHeadroom, r.Label);
            var current = File.Exists(ConfigPath) ? File.ReadAllText(ConfigPath) : null;
            if (current != text) ApoInstaller.WriteAtomic(ConfigPath, text);
            DeleteStaleImpulses(irFile);
            LastPlan = plan;
            return plan;
        }
    }

    private void DeleteStaleImpulses(string? keep)
    {
        foreach (var f in Directory.EnumerateFiles(_dir, ImpulsePrefix + "*.wav"))
        {
            if (keep is not null && Path.GetFileName(f).Equals(keep, StringComparison.OrdinalIgnoreCase)) continue;
            try { File.Delete(f); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* in use by APO; retried next time */ }
        }
    }

    /// <summary>Hash of everything the impulse response depends on, so it is only re-rendered when the room changes.</summary>
    public static string ImpulseKey(SoundSettings s, byte[]? userImpulse)
    {
        var desc = string.Create(CultureInfo.InvariantCulture,
            $"v1|{s.Environment}|{s.EnvironmentIntensity:R}|{s.SpatialEnabled}|{s.RoomSize:R}|{s.ConvolutionEnabled && userImpulse is not null}|{s.ConvolutionMix:R}|");
        var bytes = Encoding.UTF8.GetBytes(desc).Concat(userImpulse ?? []).ToArray();
        return Convert.ToHexStringLower(SHA256.HashData(bytes))[..16];
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            _timer.Dispose();
        }
        Flush(); // keep the last change
    }
}
