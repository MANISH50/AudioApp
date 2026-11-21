using System.Security.Cryptography;
using System.Text;
using Spatia.Core.Models;

namespace Spatia.Core.Logic;

/// <summary>Windows endpoint form factors (EndpointFormFactor enum in mmdeviceapi.h).</summary>
public enum EndpointFormFactor
{
    RemoteNetworkDevice = 0, Speakers = 1, LineLevel = 2, Headphones = 3, Microphone = 4, Headset = 5, Handset = 6,
    UnknownDigitalPassthrough = 7, Spdif = 8, DigitalAudioDisplayDevice = 9, UnknownFormFactor = 10,
}

/// <summary>
/// Maps a Windows audio endpoint to Spatia's output classes. Pure, so it is unit-tested without audio hardware.
/// Bus (enumerator) wins over form factor: Bluetooth and USB headphones report "Headphones" too.
/// </summary>
public static class OutputClassifier
{
    public static OutputType Classify(EndpointFormFactor formFactor, string? enumeratorName, string? friendlyName)
    {
        var bus = (enumeratorName ?? "").ToUpperInvariant();
        var name = (friendlyName ?? "").ToUpperInvariant();
        if (bus.StartsWith("BTH", StringComparison.Ordinal) || name.Contains("BLUETOOTH", StringComparison.Ordinal)) return OutputType.Bluetooth;
        if (bus == "USB") return OutputType.Usb;
        return formFactor switch
        {
            EndpointFormFactor.Headphones or EndpointFormFactor.Headset or EndpointFormFactor.Handset => OutputType.Wired,
            EndpointFormFactor.DigitalAudioDisplayDevice or EndpointFormFactor.Spdif or EndpointFormFactor.RemoteNetworkDevice
                or EndpointFormFactor.UnknownDigitalPassthrough => OutputType.HdmiCast,
            _ when name.Contains("HDMI", StringComparison.Ordinal) || name.Contains("DISPLAY", StringComparison.Ordinal) => OutputType.HdmiCast,
            _ => OutputType.Speaker,
        };
    }

    /// <summary>Stable, non-identifying key for per-device memory: a hash of type + name, never a hardware ID.</summary>
    public static string DeviceKey(OutputType type, string? friendlyName)
    {
        var raw = $"{(int)type}|{(friendlyName ?? "").Trim()[..Math.Min(64, (friendlyName ?? "").Trim().Length)]}";
        return Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(raw)))[..16];
    }
}
