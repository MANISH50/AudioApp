using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;
using Spatia.Core.Logic;
using Spatia.Core.Settings;

namespace Spatia.Audio;

/// <summary>Tracks active output devices and the default device through documented Core Audio notifications.</summary>
public sealed class DeviceMonitor : IMMNotificationClient, IDisposable
{
    private static readonly PropertyKey FormFactorKey = new(new Guid("1da5d803-d492-4edd-8c23-e0c0ffee7f0e"), 0);
    private static readonly PropertyKey EnumeratorNameKey = new(new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), 24);

    private readonly MMDeviceEnumerator _enumerator = new();

    /// <summary>Raised on a Core Audio thread when devices or the default device change.</summary>
    public event Action? Changed;
    /// <summary>Raised when Windows' default playback device changes (the player re-opens its output).</summary>
    public event Action? DefaultChanged;

    public IReadOnlyList<ConnectedOutput> Outputs { get; private set; } = [];
    public string? DefaultDeviceId { get; private set; }
    public string? DefaultDeviceName { get; private set; }

    public DeviceMonitor()
    {
        Refresh();
        _enumerator.RegisterEndpointNotificationCallback(this);
    }

    public void Refresh()
    {
        var list = new List<ConnectedOutput>();
        foreach (var d in _enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            try
            {
                var ff = d.Properties.Contains(FormFactorKey) ? (EndpointFormFactor)Convert.ToInt32(d.Properties[FormFactorKey].Value, System.Globalization.CultureInfo.InvariantCulture) : EndpointFormFactor.UnknownFormFactor;
                var bus = d.Properties.Contains(EnumeratorNameKey) ? d.Properties[EnumeratorNameKey].Value as string : null;
                var type = OutputClassifier.Classify(ff, bus, d.FriendlyName);
                list.Add(new ConnectedOutput(type, d.FriendlyName, OutputClassifier.DeviceKey(type, d.FriendlyName), d.ID));
            }
            catch (Exception) { /* a device vanished mid-enumeration */ }
            finally { d.Dispose(); }
        }
        Outputs = list;
        try
        {
            using var def = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            DefaultDeviceId = def.ID; DefaultDeviceName = def.FriendlyName;
        }
        catch (Exception) { DefaultDeviceId = null; DefaultDeviceName = null; }
    }

    private void Notify(bool defaultChanged = false)
    {
        Refresh();
        if (defaultChanged) DefaultChanged?.Invoke();
        Changed?.Invoke();
    }

    public void OnDeviceStateChanged(string deviceId, DeviceState newState) => Notify();
    public void OnDeviceAdded(string pwstrDeviceId) => Notify();
    public void OnDeviceRemoved(string deviceId) => Notify();
    public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId)
    {
        if (flow == DataFlow.Render && role == Role.Multimedia) Notify(defaultChanged: true);
    }
    public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key) { }

    public void Dispose()
    {
        _enumerator.UnregisterEndpointNotificationCallback(this);
        _enumerator.Dispose();
    }
}
