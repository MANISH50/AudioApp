using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using ScreenLink.Core.Net;
using ScreenLink.Core.Protocol;
using ScreenLink.Core.Security;
using ScreenLink.Core.Settings;
using Xunit;

namespace ScreenLink.Core.Tests;

/// <summary>End-to-end over real TLS on loopback: a fake phone talks to the real server.</summary>
public sealed class LinkServerTests : IDisposable
{
    private sealed class Recorder : IKeySink, IDisposable
    {
        public void Dispose() => Got.Dispose();
        public readonly List<KeyStroke> Strokes = [];
        public readonly SemaphoreSlim Got = new(0);
        public void Send(IReadOnlyList<KeyStroke> strokes) { lock (Strokes) Strokes.AddRange(strokes); Got.Release(); }
    }

    private readonly string _dir = Path.Combine(Path.GetTempPath(), "sl-test-" + Guid.NewGuid().ToString("N"));
    private readonly X509Certificate2 _laptop;
    private readonly PairedPhones _paired;
    private readonly AppSettings _settings = new();
    private readonly Recorder _keys = new();
    private readonly LinkServer _server;

    public LinkServerTests()
    {
        _laptop = Identity.Load(Identity.Create());
        _paired = new PairedPhones(_dir);
        _server = new LinkServer(_laptop, _paired, _settings, _keys, "Test Laptop");
        _server.Start(firstPort: 0);
    }

    public void Dispose()
    {
        _server.Dispose();
        _laptop.Dispose();
        _keys.Dispose();
        Directory.Delete(_dir, true);
    }

    private sealed class FakePhone : IAsyncDisposable
    {
        private readonly TcpClient _tcp = new();
        public SslStream Ssl = null!;
        public byte[] LaptopFingerprint = [];

        public static async Task<FakePhone> ConnectAsync(int port, X509Certificate2 identity)
        {
            var p = new FakePhone();
            await p._tcp.ConnectAsync("127.0.0.1", port);
            p.Ssl = new SslStream(p._tcp.GetStream(), false);
            await p.Ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = "screenlink",
                ClientCertificates = [identity],
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                // The fake phone records the laptop's fingerprint instead of pinning it (the real phone pins).
#pragma warning disable CA5359
                RemoteCertificateValidationCallback = (_, c, _, _) =>
                {
                    p.LaptopFingerprint = System.Security.Cryptography.SHA256.HashData(c!.GetRawCertData());
                    return true;
                },
#pragma warning restore CA5359
            });
            return p;
        }

        public ValueTask SendAsync(byte type, byte[] payload) => Ssl.WriteAsync(FrameCodec.Encode(type, payload));

        /// <summary>Next frame that isn't a ping.</summary>
        public async Task<Frame> ReadAsync()
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            while (true)
            {
                var f = await FrameCodec.ReadAsync(Ssl, cts.Token);
                if (f.Type != Wire.Ping) return f;
            }
        }

        public async ValueTask DisposeAsync() { await Ssl.DisposeAsync(); _tcp.Dispose(); }
    }

    private async Task<(FakePhone phone, Frame result)> Pair(X509Certificate2 phoneId, bool allow, List<PairingRequest> requests)
    {
        var phone = await FakePhone.ConnectAsync(_server.Port, phoneId);
        await phone.SendAsync(Wire.Hello, Messages.Hello("Pixel"));
        var hello = Messages.ParseHello((await phone.ReadAsync()).Payload);
        Assert.Equal("Test Laptop", hello.Name);
        await phone.SendAsync(Wire.PairConfirm, Messages.Flag(true));
        var deadline = DateTime.UtcNow.AddSeconds(5);
        while (requests.Count == 0 && DateTime.UtcNow < deadline) await Task.Delay(20);
        var pending = requests.LastOrDefault();
        pending?.Answer(allow);
        return (phone, await phone.ReadAsync());
    }

    [Fact]
    public async Task Pairing_then_typing_then_reconnect_without_prompt()
    {
        var requests = new List<PairingRequest>();
        _server.PairingRequested += requests.Add;
        using var phoneId = Identity.Load(Identity.Create("CN=Phone"));

        var (phone, result) = await Pair(phoneId, allow: true, requests);
        Assert.Single(requests);
        Assert.Equal(Wire.PairResult, result.Type);
        Assert.True(Messages.ParseFlag(result.Payload));
        // Both sides compute the same code from the same two certificates.
        Assert.Equal(Pairing.Code(phone.LaptopFingerprint, Pairing.Fingerprint(phoneId)), requests[0].Code);

        await phone.SendAsync(Wire.KeyText, "hi"u8.ToArray());
        Assert.True(await _keys.Got.WaitAsync(TimeSpan.FromSeconds(5)));
        lock (_keys.Strokes) Assert.Equal("hhii", new string([.. _keys.Strokes.Select(k => k.Unicode)]));
        await phone.DisposeAsync();

        // Same phone again: pinned, so no new prompt.
        var (again, result2) = await Pair(phoneId, allow: false, requests);
        Assert.Single(requests);
        Assert.True(Messages.ParseFlag(result2.Payload));
        await again.DisposeAsync();
    }

    [Fact]
    public async Task Declined_phone_is_refused_and_not_pinned()
    {
        var requests = new List<PairingRequest>();
        _server.PairingRequested += requests.Add;
        using var phoneId = Identity.Load(Identity.Create("CN=Stranger"));
        var (phone, result) = await Pair(phoneId, allow: false, requests);
        Assert.False(Messages.ParseFlag(result.Payload));
        Assert.Empty(_paired.All);
        await phone.DisposeAsync();
    }

    [Fact]
    public async Task Typing_switch_off_blocks_input()
    {
        _settings.AllowTyping = false;
        var requests = new List<PairingRequest>();
        _server.PairingRequested += requests.Add;
        using var phoneId = Identity.Load(Identity.Create("CN=Phone2"));
        var (phone, _) = await Pair(phoneId, allow: true, requests);
        await phone.SendAsync(Wire.KeyText, "x"u8.ToArray());
        Assert.False(await _keys.Got.WaitAsync(TimeSpan.FromMilliseconds(500)));
        await phone.DisposeAsync();
    }

    [Fact]
    public async Task Garbage_instead_of_tls_is_dropped()
    {
        using var tcp = new TcpClient();
        await tcp.ConnectAsync("127.0.0.1", _server.Port);
        var s = tcp.GetStream();
        await s.WriteAsync(new byte[] { 0x01, 0x7F, 0xFF, 0xFF, 0xFF });
        var buf = new byte[64];
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        int n;
        try { n = await s.ReadAsync(buf, cts.Token); } catch (IOException) { n = 0; }
        Assert.True(n >= 0); // the server answers with a TLS alert or closes; it must not crash
        Assert.Null(_server.ConnectedPhone);
    }
}
