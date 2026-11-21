using System.Globalization;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace ScreenLink.Core.Security;

/// <summary>
/// Pairing by numeric comparison (like Bluetooth): both devices show a 6-digit code computed from both certificate
/// fingerprints. A machine-in-the-middle must present its own certificates, which changes the code.
/// </summary>
public static class Pairing
{
    private static readonly byte[] Label = Encoding.ASCII.GetBytes("ScreenLink-pair-v1");

    public static byte[] Fingerprint(X509Certificate2 cert)
    {
        ArgumentNullException.ThrowIfNull(cert);
        return SHA256.HashData(cert.RawData);
    }

    /// <summary>SHA-256(label ‖ laptopFingerprint ‖ phoneFingerprint), first 4 bytes as an unsigned big-endian number mod 1 000 000.</summary>
    public static string Code(byte[] laptopFingerprint, byte[] phoneFingerprint)
    {
        ArgumentNullException.ThrowIfNull(laptopFingerprint);
        ArgumentNullException.ThrowIfNull(phoneFingerprint);
        if (laptopFingerprint.Length != 32 || phoneFingerprint.Length != 32) throw new ArgumentException("fingerprints must be SHA-256");
        var h = SHA256.HashData([.. Label, .. laptopFingerprint, .. phoneFingerprint]);
        var v = ((uint)h[0] << 24) | ((uint)h[1] << 16) | ((uint)h[2] << 8) | h[3];
        return (v % 1_000_000).ToString("D6", CultureInfo.InvariantCulture);
    }

    public static string ToHex(byte[] b) => Convert.ToHexStringLower(b);
}
