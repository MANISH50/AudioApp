using ScreenLink.Core.Security;
using Xunit;

namespace ScreenLink.Core.Tests;

public class PairingTests
{
    [Fact]
    public void Code_matches_shared_test_vector()
    {
        // Same vector as PairingTest.code_matches_shared_test_vector on Android.
        var laptop = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        var phone = Enumerable.Range(32, 32).Select(i => (byte)i).ToArray();
        Assert.Equal("337226", Pairing.Code(laptop, phone));
    }

    [Fact]
    public void Identity_is_created_once_and_reloaded()
    {
        var dir = Path.Combine(Path.GetTempPath(), "sl-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            static byte[] Same(byte[] b) => b;
            using var a = Identity.LoadOrCreate(dir, Same, Same);
            using var b = Identity.LoadOrCreate(dir, Same, Same);
            Assert.True(a.HasPrivateKey);
            Assert.Equal(Pairing.Fingerprint(a), Pairing.Fingerprint(b));
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Corrupt_identity_is_replaced()
    {
        var dir = Path.Combine(Path.GetTempPath(), "sl-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(dir);
            File.WriteAllBytes(Path.Combine(dir, Identity.FileName), [1, 2, 3]);
            using var c = Identity.LoadOrCreate(dir, b => b, b => b);
            Assert.True(c.HasPrivateKey);
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void Paired_phones_persist()
    {
        var dir = Path.Combine(Path.GetTempPath(), "sl-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            var fp = new string('c', 64);
            new PairedPhones(dir).Add(fp, "Pixel");
            var again = new PairedPhones(dir);
            Assert.True(again.IsPaired(fp));
            again.Remove(fp);
            Assert.False(new PairedPhones(dir).IsPaired(fp));
        }
        finally { Directory.Delete(dir, true); }
    }
}
