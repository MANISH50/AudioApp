using NAudio.CoreAudioApi;

namespace Spatia.Audio;

/// <summary>Reads the default device's master volume (for the high-volume warning and equal-loudness compensation).</summary>
public sealed class VolumeMonitor : IDisposable
{
    private MMDevice? _device;
    public event Action? Changed;

    /// <summary>0..1 slider position.</summary>
    public float Fraction { get; private set; }
    /// <summary>How far below maximum the endpoint is, in dB (≥ 0).</summary>
    public float AttenuationDb { get; private set; }

    public VolumeMonitor() => Rebind();

    /// <summary>Call when the default device changes.</summary>
    public void Rebind()
    {
        Unbind();
        try
        {
            _device = new MMDeviceEnumerator().GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            _device.AudioEndpointVolume.OnVolumeNotification += OnVolume;
            Read();
        }
        catch (Exception) { _device = null; Fraction = 0; AttenuationDb = 0; }
        Changed?.Invoke();
    }

    private void OnVolume(AudioVolumeNotificationData data) { Read(); Changed?.Invoke(); }

    private void Read()
    {
        if (_device is null) return;
        var v = _device.AudioEndpointVolume;
        Fraction = v.Mute ? 0 : v.MasterVolumeLevelScalar;
        var db = -v.MasterVolumeLevel;
        AttenuationDb = float.IsFinite(db) ? Math.Clamp(db, 0f, 90f) : (1 - Fraction) * 60f;
    }

    /// <summary>Lower the device volume to a safe level (offered from the warning dialog).</summary>
    public void SetFraction(float fraction)
    {
        if (_device is null) return;
        _device.AudioEndpointVolume.MasterVolumeLevelScalar = Math.Clamp(fraction, 0f, 1f);
    }

    private void Unbind()
    {
        if (_device is null) return;
        try { _device.AudioEndpointVolume.OnVolumeNotification -= OnVolume; } catch (Exception) { }
        _device.Dispose();
        _device = null;
    }

    public void Dispose() => Unbind();
}
