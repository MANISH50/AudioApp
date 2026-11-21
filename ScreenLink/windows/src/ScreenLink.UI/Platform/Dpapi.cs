using System.Security.Cryptography;

namespace ScreenLink.UI.Platform;

/// <summary>Windows Data Protection (DPAPI), current-user scope: only this Windows account can decrypt.</summary>
public static class Dpapi
{
    private static readonly byte[] Entropy = "ScreenLink identity v1"u8.ToArray();
    public static byte[] Protect(byte[] data) => ProtectedData.Protect(data, Entropy, DataProtectionScope.CurrentUser);
    public static byte[] Unprotect(byte[] data) => ProtectedData.Unprotect(data, Entropy, DataProtectionScope.CurrentUser);
}
