using System.Diagnostics;
using Microsoft.UI.Dispatching;
using Spatia.Audio;
using Spatia.Core.Apo;
using Spatia.Core.Logic;
using Spatia.Core.Models;
using Spatia.Core.Native;
using Spatia.Core.Settings;

namespace Spatia.UI;

/// <summary>
/// Owns the app's state and services (settings, devices, volume, Equalizer APO connection) and writes the effective
/// settings to Equalizer APO, which applies them to every app. Raises <see cref="Changed"/> on the UI thread whenever
/// anything visible may have changed.
/// </summary>
public sealed class AppController : IDisposable
{
    private const string ImpulseFile = "impulse.wav";
    private readonly DispatcherQueue _ui;
    private readonly SettingsStore _store;
    private readonly ProfileResolver _resolver = new(new NativeEngineLogic());
    private readonly DispatcherQueueTimer _tick;
    private ApoConfigWriter? _writer;
    private string? _writerDir;
    private byte[]? _impulse;
    private float[]? _testClip;
    private int _apoPoll;

    public DeviceMonitor Devices { get; }
    public VolumeMonitor Volume { get; }
    public TonePlayer Tones { get; } = new();

    public AppState State { get; private set; }
    public EffectiveProfile Profile { get; private set; }
    public SoundSettings Settings => Profile.Settings;
    public bool HasImpulse => _impulse is not null;

    /// <summary>Equalizer APO's state (refreshed every few seconds and after connect/disconnect).</summary>
    public ApoStatus Apo { get; private set; }
    /// <summary>The plan last written to Equalizer APO (null until the first write).</summary>
    public ApoPlan? LastPlan { get; private set; }
    public string? ApoError { get; private set; }

    /// <summary>Raised on the UI thread.</summary>
    public event Action? Changed;
    /// <summary>Raised on the UI thread when the volume is above the safe level for this output.</summary>
    public event Action? VolumeWarning;

    public AppController(DispatcherQueue ui)
    {
        _ui = ui;
        _store = new SettingsStore(SettingsStore.DefaultDirectory, Engine.Validate);
        State = _store.Load();
        Devices = new DeviceMonitor();
        Volume = new VolumeMonitor();
        Profile = _resolver.Resolve(State, Devices.Outputs, Devices.DefaultDeviceId);
        _impulse = LoadSavedImpulse();
        Apo = ApoLocator.Detect();

        Devices.Changed += () => OnUi(Reapply);
        Devices.DefaultChanged += () => OnUi(Volume.Rebind);
        Volume.Changed += () => OnUi(() => { Reapply(); CheckVolume(); });

        _tick = ui.CreateTimer();
        _tick.Interval = TimeSpan.FromSeconds(1);
        _tick.Tick += (_, _) => OnSecond();
        _tick.Start();
        Reapply();
    }

    private void OnUi(Action a) => _ui.TryEnqueue(() => a());

    // ---------------- applying settings (to Equalizer APO) ----------------

    private void Reapply()
    {
        Profile = _resolver.Resolve(State, Devices.Outputs, Devices.DefaultDeviceId);
        WriteToApo();
        Changed?.Invoke();
    }

    private void WriteToApo()
    {
        if (Apo.State != ApoState.Connected || Apo.SpatiaDir is not { } dir) return;
        if (_writer is null || _writerDir != dir)
        {
            _writer?.Dispose();
            _writer = new ApoConfigWriter(dir);
            _writerDir = dir;
            _writer.Written += plan => OnUi(() => { LastPlan = plan; ApoError = null; Changed?.Invoke(); });
            _writer.Failed += e => OnUi(() => { AppLog.Error("apo write", e); ApoError = e.Message; Changed?.Invoke(); });
        }
        var label = $"Mode {Settings.Mode}, output {Profile.Output}, Spatia {typeof(AppController).Assembly.GetName().Version}";
        _writer.Schedule(new ApoRequest(Settings, Profile.Output, Volume.AttenuationDb, _impulse, label));
    }

    private void Save(AppState next)
    {
        State = next;
        _store.Save(State);
        Reapply();
    }

    /// <summary>Edits the settings of the active output (per device if remembered), keeping the current mode.</summary>
    public void Edit(Func<SoundSettings, SoundSettings> change) =>
        Save(ProfileResolver.Store(State, Profile, Engine.Validate(change(Settings))));

    /// <summary>Like <see cref="Edit"/>, but a tweak to a preset turns it into Custom (the preset itself stays intact).</summary>
    public void Tweak(Func<SoundSettings, SoundSettings> change) => Edit(s => change(s) with { Mode = Mode.Custom });

    public void ApplyMode(Mode mode) => Edit(current =>
        mode == Mode.Custom ? (State.CustomPreset ?? current) with { Mode = Mode.Custom } : Engine.Preset(mode, current));

    public void SetManualOutput(OutputType? t) => Save(State with { ManualOutput = t });
    public void SetRememberPerDevice(bool v) => Save(State with { RememberPerDevice = v });

    // ---------------- Equalizer APO ----------------

    public void RefreshApo()
    {
        var before = Apo;
        Apo = ApoLocator.Detect();
        if (before.State != Apo.State || before.ConfigDir != Apo.ConfigDir || !before.Devices.SequenceEqual(Apo.Devices))
        {
            if (Apo.State == ApoState.Connected) WriteToApo();
            Changed?.Invoke();
        }
    }

    /// <summary>
    /// Connects Spatia to Equalizer APO. Runs in-process when config.txt is writable (portable/test setups); otherwise
    /// starts this exe elevated (one UAC prompt) with --apo-connect &lt;your SID&gt;. Returns false if it didn't work.
    /// </summary>
    public async Task<bool> ConnectApoAsync() => await RunApoCommandAsync(connect: true);

    public async Task<bool> DisconnectApoAsync() => await RunApoCommandAsync(connect: false);

    private async Task<bool> RunApoCommandAsync(bool connect)
    {
        ApoError = null;
        var status = ApoLocator.Detect();
        if (status.ConfigDir is null) { RefreshApo(); return false; }
        var ok = false;
        try
        {
            if (!status.NeedsElevation)
            {
                if (connect) ApoInstaller.Connect(status.ConfigDir, null); else ApoInstaller.Disconnect(status.ConfigDir);
                ok = true;
            }
            else
            {
                var sid = System.Security.Principal.WindowsIdentity.GetCurrent().User?.Value;
                var args = connect ? $"{ApoCommandLine.ConnectArg} {sid}" : ApoCommandLine.DisconnectArg;
                ok = await Task.Run(() => RunElevated(args)) == 0;
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            AppLog.Error("apo connect", e);
            ApoError = e.Message;
        }
        if (!connect && ok && _writer is not null) { _writer.Dispose(); _writer = null; _writerDir = null; }
        Apo = ApoLocator.Detect();
        if (Apo.State == ApoState.Connected) WriteToApo();
        Changed?.Invoke();
        return ok && (Apo.State == ApoState.Connected) == connect;
    }

    /// <summary>Starts Spatia.exe elevated with <paramref name="args"/> and returns its exit code (1223 = UAC cancelled).</summary>
    private static int RunElevated(string args)
    {
        var exe = System.Environment.ProcessPath ?? throw new InvalidOperationException("Cannot find Spatia.exe.");
        var psi = new ProcessStartInfo(exe, args) { Verb = "runas", UseShellExecute = true, WorkingDirectory = AppContext.BaseDirectory };
        try
        {
            using var p = Process.Start(psi) ?? throw new InvalidOperationException("Could not start the helper.");
            p.WaitForExit();
            return p.ExitCode;
        }
        catch (System.ComponentModel.Win32Exception e) when (e.NativeErrorCode == 1223) { return 1223; }
    }

    public static void OpenApoDownload() => OpenUrl(ApoLocator.DownloadUrl);

    /// <summary>Opens Equalizer APO's Device Selector (it asks for administrator rights itself).</summary>
    public bool OpenDeviceSelector()
    {
        if (Apo.DeviceSelectorPath is not { } path || !File.Exists(path)) return false;
        try { using var _ = Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); return true; }
        catch (System.ComponentModel.Win32Exception e) { AppLog.Error("device selector", e); return false; }
    }

    private static void OpenUrl(string url)
    {
        try { using var _ = Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (System.ComponentModel.Win32Exception e) { AppLog.Error("open url", e); }
    }

    // ---------------- test sound ----------------

    public const int TestSeconds = 4;

    /// <summary>
    /// Plays ~4 s of the engine's sample music to the default device without any Spatia processing, so what you hear
    /// is what Equalizer APO does to all apps.
    /// </summary>
    public void PlayTestSound()
    {
        const int sr = 48000;
        if (_testClip is null)
        {
            var track = Engine.RenderSampleTrack(sr);
            var frames = Math.Min(track.Length / 2, TestSeconds * sr);
            var clip = new float[frames * 2];
            Array.Copy(track, clip, clip.Length);
            var fade = sr / 10;
            for (var i = 0; i < fade && i < frames; i++)
            {
                var g = i / (float)fade;
                clip[2 * (frames - 1 - i)] *= g; clip[2 * (frames - 1 - i) + 1] *= g;
            }
            _testClip = clip;
        }
        try
        {
            Tones.PlayClip(_testClip, sr);
            TestSoundError = null;
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or InvalidOperationException or NAudio.MmException)
        {
            // No playback device (or it is busy): say so on Home instead of failing.
            AppLog.Info("test sound", e.GetType().Name + " 0x" + e.HResult.ToString("X8", System.Globalization.CultureInfo.InvariantCulture));
            TestSoundError = e.Message;
        }
        Changed?.Invoke();
    }

    public string? TestSoundError { get; private set; }

    private void OnSecond()
    {
        if (++_apoPoll >= 3) { _apoPoll = 0; RefreshApo(); }
    }

    // ---------------- imports ----------------

    public const int MaxImpulseBytes = 192_000 * 2 * 4 * 2 + 4096;

    public AutoEqResult ImportCorrection(byte[] file)
    {
        var r = Engine.ParseAutoEq(file);
        if (r.Ok) Edit(s => s with { Correction = r.Filters, CorrectionPreampDb = r.PreampDb, CorrectionEnabled = true });
        return r;
    }

    public bool ImportImpulse(byte[] wav)
    {
        using (var probe = new DspChain(48000, 2)) if (!probe.SetImpulse(wav)) return false;
        _impulse = wav;
        try
        {
            Directory.CreateDirectory(SettingsStore.DefaultDirectory);
            ApoInstaller.WriteAtomic(Path.Combine(SettingsStore.DefaultDirectory, ImpulseFile), wav);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { AppLog.Error("save impulse", e); }
        Edit(s => s with { ConvolutionEnabled = true });
        return true;
    }

    public void ClearImpulse()
    {
        _impulse = null;
        try { File.Delete(Path.Combine(SettingsStore.DefaultDirectory, ImpulseFile)); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { AppLog.Error("delete impulse", e); }
        Edit(s => s with { ConvolutionEnabled = false });
    }

    /// <summary>The impulse response imported earlier (kept next to settings.json), re-validated by the engine.</summary>
    private static byte[]? LoadSavedImpulse()
    {
        try
        {
            var path = Path.Combine(SettingsStore.DefaultDirectory, ImpulseFile);
            var info = new FileInfo(path);
            if (!info.Exists || info.Length > MaxImpulseBytes) return null;
            var wav = File.ReadAllBytes(path);
            using var probe = new DspChain(48000, 2);
            return probe.SetImpulse(wav) ? wav : null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or NotSupportedException) { return null; }
    }

    public void SaveHearingResult(IReadOnlyList<float> left, IReadOnlyList<float> right) =>
        Edit(s => s with { HearingCompensation = HearingProfile.Compensation(left, right), HearingEnabled = true });

    // ---------------- safety ----------------

    public bool VolumeAboveSafe => Volume.Fraction > Profile.Safety.VolumeWarningFraction;

    private void CheckVolume()
    {
        var acknowledged = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - State.VolumeWarningAcknowledgedAtUnixMs < 20L * 3600 * 1000;
        if (VolumeAboveSafe && !acknowledged) VolumeWarning?.Invoke();
    }

    public void AcknowledgeVolume() => Save(State with { VolumeWarningAcknowledgedAtUnixMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() });

    public void ReduceVolumeToSafe() => Volume.SetFraction(Profile.Safety.VolumeWarningFraction);

    public void Dispose()
    {
        _tick.Stop();
        _writer?.Dispose();
        Tones.Dispose();
        Volume.Dispose();
        Devices.Dispose();
    }
}
