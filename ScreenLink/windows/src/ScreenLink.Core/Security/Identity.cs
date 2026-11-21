using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using ScreenLink.Core.Logic;

namespace ScreenLink.Core.Security;

/// <summary>
/// This laptop's TLS identity: an ECDSA P-256 key with a self-signed certificate, created on first run. Stored as a
/// PKCS#12 blob encrypted by the caller's <c>protect</c> function (Windows DPAPI, current user), so only this Windows
/// account can read it. Phones pin its fingerprint at pairing.
/// </summary>
public static class Identity
{
    public const string FileName = "identity.bin";

    public static X509Certificate2 LoadOrCreate(string directory, Func<byte[], byte[]> protect, Func<byte[], byte[]> unprotect)
    {
        ArgumentNullException.ThrowIfNull(protect);
        ArgumentNullException.ThrowIfNull(unprotect);
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, FileName);
        if (File.Exists(path))
        {
            try { return Load(unprotect(File.ReadAllBytes(path))); }
            catch (CryptographicException e) { AppLog.Error("identity", "stored identity unreadable, creating a new one: " + e.Message); }
        }
        var pfx = Create();
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, protect(pfx));
        File.Move(tmp, path, overwrite: true);
        AppLog.Info("identity", "new identity created");
        return Load(pfx);
    }

    /// <summary>A new self-signed identity as PKCS#12 bytes (no password: the whole blob is encrypted at rest).</summary>
    public static byte[] Create(string subject = "CN=ScreenLink Windows")
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var req = new CertificateRequest(subject, key, HashAlgorithmName.SHA256);
        req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, critical: true));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            [new Oid("1.3.6.1.5.5.7.3.1"), new Oid("1.3.6.1.5.5.7.3.2")], critical: false)); // server + client auth
        using var cert = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(20));
        return cert.Export(X509ContentType.Pfx);
    }

    /// <summary>
    /// Loads an identity for use as a TLS certificate. On Windows the key goes into the user key store (Schannel can't
    /// use ephemeral keys); it is removed again when the certificate is disposed.
    /// </summary>
    public static X509Certificate2 Load(byte[] pfx)
    {
        var flags = OperatingSystem.IsWindows() ? X509KeyStorageFlags.UserKeySet : X509KeyStorageFlags.DefaultKeySet;
        return X509CertificateLoader.LoadPkcs12(pfx, null, flags);
    }
}
