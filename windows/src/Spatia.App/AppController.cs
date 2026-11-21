using Microsoft.UI.Dispatching;
using Spatia.Audio;
using Spatia.Core.Logic;
using Spatia.Core.Models;
using Spatia.Core.Native;
using Spatia.Core.Settings;

namespace Spatia.App;

/// <summary>
/// Owns the app's state and services (settings, devices, volume, player) and applies the effective settings to the
/// shared engine. Raises <see cref="Changed"/> on the UI thread whenever anything visible may have changed.
/// </summary>
public sealed class AppController : IDisposable
{
    private readonly DispatcherQueue _ui;
    private readonly SettingsStore _store;
    private readonly ProfileResolver _resolver = new(new NativeEngineLogic());
    private readonly DispatcherQueueTimer _tick;
    private byte[]? _impulse;
    private int _sleepRemainingS;
    private int _fadeStep = -1;
    private int _doseCounter;

    public DeviceMonitor Devices { get; }
    public VolumeMonitor Volume { get; }
    public PlayerEngine Player { get; } = new();
    public TonePlayer Tones { get; } = new();

    public AppState State { get; private set; }
    public EffectiveProfile Profile { get; private set; }
    public SoundSettings Settings => Profile.Settings;
    public string? LastError { get; private set; }
    public int SleepMinutes { get; private set; }
    public int SleepRemainingS => _sleepRemainingS;
    public bool HasImpulse => _impulse is not null;

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
        Player.SetExclusiveMode(State.ExclusiveMode);

        Devices.Changed += () => OnUi(Reapply);
        Devices.DefaultChanged += () => OnUi(() => { Volume.Rebind(); Player.RestartOutput(); });
        Volume.Changed += () => OnUi(() => { Reapply(); CheckVolume(); });
        Player.ChainCreated += chain => ApplyTo(chain); // audio thread: the chain is thread-safe
        Player.StateChanged += () => OnUi(() => Changed?.Invoke());
        Player.Failed += e => OnUi(() => { LastError = e.ToString(); Changed?.Invoke(); });

        _tick = ui.CreateTimer();
        _tick.Interval = TimeSpan.FromSeconds(1);
        _tick.Tick += (_, _) => OnSecond();
        _tick.Start();
    }

    private void OnUi(Action a) => _ui.TryEnqueue(() => a());

    // ---------------- applying settings ----------------

    private void Reapply()
    {
        Profile = _resolver.Resolve(State, Devices.Outputs, Devices.DefaultDeviceId);
        if (Player.Chain is { } c) ApplyTo(c);
        Changed?.Invoke();
    }

    private void ApplyTo(DspChain chain)
    {
        try
        {
            chain.SetSettings(Profile.Settings, Profile.Output, Volume.AttenuationDb);
            if (_impulse is { } ir) chain.SetImpulse(ir);
        }
        catch (ObjectDisposedException) { /* replaced while applying */ }
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
    public void SetDoseTracking(bool v) => Save(State with { DoseTrackingEnabled = v });

    public void SetExclusiveMode(bool v)
    {
        Player.SetExclusiveMode(v);
        Save(State with { ExclusiveMode = Player.ExclusiveMode });
    }

    // ---------------- player ----------------

    public FormatVerdict OpenFile(string path)
    {
        LastError = null;
        var v = Player.Open(path);
        Changed?.Invoke();
        return v;
    }

    public void PlaySample()
    {
        LastError = null;
        Player.OpenSample(Engine.RenderSampleTrack(44100), 44100, App.S["SampleTitle"]);
        Changed?.Invoke();
    }

    public void ClearError() { LastError = null; Changed?.Invoke(); }

    public void SetSleepTimer(int minutes)
    {
        SleepMinutes = minutes;
        _sleepRemainingS = minutes * 60;
        _fadeStep = -1;
        Player.FadeGain = 1f;
        Changed?.Invoke();
    }

    private void OnSecond()
    {
        if (_sleepRemainingS > 0 && --_sleepRemainingS == 0) _fadeStep = 15;
        if (_fadeStep >= 0)
        {
            Player.FadeGain = _fadeStep / 15f;
            if (_fadeStep-- == 0) { Player.Pause(); Player.FadeGain = 1f; SleepMinutes = 0; }
        }
        TrackDose();
        Changed?.Invoke();
    }

    private void TrackDose()
    {
        if (!State.DoseTrackingEnabled || !Player.IsPlaying || !Profile.Safety.IsHeadphones || Player.Chain is null) return;
        if (++_doseCounter < 10) return;
        _doseCounter = 0;
        var spl = DoseTracker.EstimateSpl(Volume.Fraction, Player.Chain.Meters().RmsDb);
        var week = DoseTracker.WeekNumber(DateTimeOffset.UtcNow);
        var base0 = State.DoseWeek == week ? State.DoseFraction : 0;
        Save(State with { DoseWeek = week, DoseFraction = base0 + DoseTracker.DoseFraction(spl, 10) });
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
        using var probe = new DspChain(48000, 2);
        if (!probe.SetImpulse(wav)) return false;
        _impulse = wav;
        Edit(s => s with { ConvolutionEnabled = true });
        return true;
    }

    public void ClearImpulse()
    {
        _impulse = null;
        Player.Chain?.SetImpulse([]);
        Edit(s => s with { ConvolutionEnabled = false });
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
        Player.Dispose();
        Tones.Dispose();
        Volume.Dispose();
        Devices.Dispose();
    }
}
