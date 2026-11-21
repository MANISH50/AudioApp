using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using ScreenLink.Core.Logic;
using ScreenLink.Core.Protocol;
using ScreenLink.Core.Security;
using ScreenLink.Core.Settings;

namespace ScreenLink.Core.Net;

/// <summary>Receives key strokes to inject into Windows (SendInput in the app; a recorder in tests).</summary>
public interface IKeySink
{
    void Send(IReadOnlyList<KeyStroke> strokes);
}

/// <summary>A phone asking to pair. The user compares <see cref="Code"/> with the phone and calls <see cref="Answer"/>.</summary>
public sealed class PairingRequest
{
    private readonly TaskCompletionSource<bool> _tcs = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public PairingRequest(string phoneName, string code) { PhoneName = phoneName; Code = code; }
    public string PhoneName { get; }
    public string Code { get; }
    internal Task<bool> Decision => _tcs.Task;
    public void Answer(bool allow) => _tcs.TrySetResult(allow);
}

/// <summary>
/// The laptop end of ScreenLink: a TLS server (TCP 47420, or the next free port up to 47429) that requires a client
/// certificate. A phone whose certificate fingerprint is pinned connects directly; any other phone triggers a pairing
/// request that the user must allow (after comparing codes) while the phone's user confirms on their side. One phone is
/// active at a time; a newly authenticated phone replaces the previous one.
/// </summary>
public sealed class LinkServer : IDisposable
{
    private static readonly TimeSpan HandshakeTimeout = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan PairingTimeout = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan IdleTimeout = TimeSpan.FromSeconds(20);

    private readonly X509Certificate2 _cert;
    private readonly byte[] _ourFingerprint;
    private readonly PairedPhones _paired;
    private readonly AppSettings _settings;
    private readonly IKeySink _keys;
    private readonly string _laptopName;
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _handshakes = new(4, 4);
    private readonly Lock _gate = new();
    private readonly Queue<DateTime> _recentPairings = new();
    private TcpListener? _listener;
    private PhoneSession? _active;
    private bool _pairingOpen;

    public LinkServer(X509Certificate2 cert, PairedPhones paired, AppSettings settings, IKeySink keys, string laptopName)
    {
        _cert = cert;
        _ourFingerprint = Pairing.Fingerprint(cert);
        _paired = paired;
        _settings = settings;
        _keys = keys;
        _laptopName = laptopName;
    }

    public int Port { get; private set; }
    public string FingerprintHex => Pairing.ToHex(_ourFingerprint);

    public event Action<PairingRequest>? PairingRequested;
    /// <summary>The pending pairing request ended (answered, timed out or the phone gave up).</summary>
    public event Action<PairingRequest>? PairingEnded;
    public event Action<string>? PhoneConnected;
    public event Action? PhoneDisconnected;
    public event Action<bool>? CastStateChanged;
    public event Action<VideoConfigMessage>? VideoConfigReceived;
    public event Action<VideoFrameMessage>? VideoFrameReceived;

    public string? ConnectedPhone { get { lock (_gate) return _active?.Name; } }

    public void Start(int firstPort = Wire.TcpPort, int attempts = 10)
    {
        for (var i = 0; i < attempts; i++)
        {
            var l = new TcpListener(IPAddress.Any, firstPort == 0 ? 0 : firstPort + i);
            try
            {
                l.Server.ExclusiveAddressUse = true;
                l.Start();
                _listener = l;
                Port = ((IPEndPoint)l.LocalEndpoint).Port;
                AppLog.Info("server", $"listening on port {Port}");
                _ = Task.Run(AcceptLoopAsync, CancellationToken.None);
                return;
            }
            catch (SocketException e) when (e.SocketErrorCode == SocketError.AddressAlreadyInUse)
            {
                l.Dispose();
            }
        }
        throw new IOException("No free port for ScreenLink");
    }

    public void RequestKeyframe() => Active()?.Send(Wire.RequestKeyframe, []);

    public void DisconnectPhone()
    {
        PhoneSession? s;
        lock (_gate) { s = _active; _active = null; }
        s?.Close(sendBye: true);
    }

    private PhoneSession? Active() { lock (_gate) return _active; }

    private async Task AcceptLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await _listener!.AcceptTcpClientAsync(_cts.Token).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException e) { AppLog.Info("server", "accept: " + e.SocketErrorCode); continue; }

            if (!_handshakes.Wait(0)) { client.Dispose(); continue; }
            _ = Task.Run(async () =>
            {
                try { await HandleAsync(client).ConfigureAwait(false); }
                catch (Exception e) when (e is IOException or AuthenticationException or ProtocolException or OperationCanceledException or TimeoutException
                                           or ObjectDisposedException or InvalidOperationException or SocketException)
                {
                    AppLog.Info("session", "ended: " + e.GetType().Name);
                }
                finally { client.Dispose(); }
            });
        }
    }

    private bool TryBeginPairing()
    {
        lock (_gate)
        {
            while (_recentPairings.Count > 0 && DateTime.UtcNow - _recentPairings.Peek() > TimeSpan.FromMinutes(1)) _recentPairings.Dequeue();
            if (_pairingOpen || _recentPairings.Count >= 5) return false;
            _pairingOpen = true;
            _recentPairings.Enqueue(DateTime.UtcNow);
            return true;
        }
    }

    private void EndPairing() { lock (_gate) _pairingOpen = false; }

    private static string CleanName(string name)
    {
        var s = new string([.. name.Where(c => !char.IsControl(c))]).Trim();
        if (s.Length > 64) s = s[..64];
        return s.Length == 0 ? "Phone" : s;
    }

    private async Task HandleAsync(TcpClient client)
    {
        PhoneSession? session = null;
        var handshakeReleased = false;
        try
        {
            client.NoDelay = true;
            var ssl = new SslStream(client.GetStream(), leaveInnerStreamOpen: false);
            using (var hs = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token))
            {
                hs.CancelAfter(HandshakeTimeout);
                await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                {
                    ServerCertificate = _cert,
                    ClientCertificateRequired = true,
                    EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                    CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                    // Phones have self-signed certificates; trust comes from the pinned fingerprint or the pairing below.
                    RemoteCertificateValidationCallback = (_, cert, _, _) => cert is not null,
                }, hs.Token).ConfigureAwait(false);
            }
            if (ssl.RemoteCertificate is null) { await ssl.DisposeAsync().ConfigureAwait(false); return; }
            using var phoneCert = X509CertificateLoader.LoadCertificate(ssl.RemoteCertificate.GetRawCertData());
            var phoneFp = Pairing.Fingerprint(phoneCert);
            var phoneFpHex = Pairing.ToHex(phoneFp);

            session = new PhoneSession(ssl, _cts.Token);
            session.Send(Wire.Hello, Messages.Hello(_laptopName));

            var first = await session.ReadAsync(HandshakeTimeout).ConfigureAwait(false);
            if (first.Type != Wire.Hello) throw new ProtocolException("expected HELLO");
            var hello = Messages.ParseHello(first.Payload);
            if (hello.Version != Wire.Version) { session.Close(sendBye: true); return; }
            session.Name = CleanName(hello.Name);

            // From here the connection can wait a long time (the user reading a code): stop counting it as a handshake.
            _handshakes.Release();
            handshakeReleased = true;

            var paired = _paired.IsPaired(phoneFpHex);
            var confirm = ReadUntilConfirmAsync(session);
            PairingRequest? request = null;
            bool allowed;
            if (paired)
            {
                allowed = await confirm.WaitAsync(PairingTimeout + TimeSpan.FromSeconds(15)).ConfigureAwait(false);
            }
            else
            {
                if (!TryBeginPairing()) { AppLog.Info("pairing", "refused: another pairing is open or too many attempts"); session.Close(sendBye: true); return; }
                try
                {
                    request = new PairingRequest(session.Name, Pairing.Code(_ourFingerprint, phoneFp));
                    PairingRequested?.Invoke(request);
                    var decision = request.Decision;
                    var done = await Task.WhenAny(confirm, decision).WaitAsync(PairingTimeout).ConfigureAwait(false);
                    if (done == decision && !decision.Result) allowed = false;
                    else
                    {
                        var phoneOk = await confirm.WaitAsync(PairingTimeout).ConfigureAwait(false);
                        allowed = phoneOk && await decision.WaitAsync(PairingTimeout).ConfigureAwait(false);
                    }
                }
                catch (TimeoutException) { allowed = false; }
                finally
                {
                    EndPairing();
                    if (request is not null) { request.Answer(false); PairingEnded?.Invoke(request); }
                }
            }

            if (!allowed)
            {
                AppLog.Info("pairing", "declined");
                session.Send(Wire.PairResult, Messages.Flag(false));
                await Task.Delay(200).ConfigureAwait(false);
                session.Close(sendBye: false);
                return;
            }
            if (!paired) { _paired.Add(phoneFpHex, session.Name); AppLog.Info("pairing", "new phone paired"); }
            session.Send(Wire.PairResult, Messages.Flag(true));

            PhoneSession? previous;
            lock (_gate) { previous = _active; _active = session; }
            previous?.Close(sendBye: true);
            PhoneConnected?.Invoke(session.Name);
            AppLog.Info("session", "phone connected");
            await RunAsync(session).ConfigureAwait(false);
        }
        finally
        {
            if (!handshakeReleased) _handshakes.Release();
            if (session is not null)
            {
                var wasActive = false;
                lock (_gate) { if (_active == session) { _active = null; wasActive = true; } }
                session.Close(sendBye: false);
                if (wasActive)
                {
                    if (session.Casting) CastStateChanged?.Invoke(false);
                    PhoneDisconnected?.Invoke();
                    AppLog.Info("session", "phone disconnected");
                }
            }
        }
    }

    /// <summary>Reads until the phone's PAIR_CONFIRM, answering pings meanwhile.</summary>
    private static async Task<bool> ReadUntilConfirmAsync(PhoneSession s)
    {
        while (true)
        {
            var f = await s.ReadAsync(PairingTimeout + TimeSpan.FromSeconds(15)).ConfigureAwait(false);
            switch (f.Type)
            {
                case Wire.PairConfirm: return Messages.ParseFlag(f.Payload);
                case Wire.Ping: s.Send(Wire.Pong, f.Payload.Length <= 8 ? f.Payload : f.Payload[..8]); break;
                case Wire.Bye: return false;
                default: break;
            }
        }
    }

    private async Task RunAsync(PhoneSession s)
    {
        using var pinger = new Timer(_ => s.Send(Wire.Ping, BitConverter.GetBytes(Environment.TickCount64)), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));
        while (true)
        {
            var f = await s.ReadAsync(IdleTimeout).ConfigureAwait(false);
            switch (f.Type)
            {
                case Wire.VideoConfig: VideoConfigReceived?.Invoke(Messages.ParseVideoConfig(f.Payload)); break;
                case Wire.VideoFrame: VideoFrameReceived?.Invoke(Messages.ParseVideoFrame(f.Payload)); break;
                case Wire.CastState:
                    s.Casting = Messages.ParseFlag(f.Payload);
                    CastStateChanged?.Invoke(s.Casting);
                    break;
                case Wire.KeyText:
                    var text = Messages.ParseKeyText(f.Payload);
                    if (_settings.AllowTyping) _keys.Send(KeyMapping.Text(text));
                    break;
                case Wire.KeyPress:
                    var k = Messages.ParseKeyPress(f.Payload);
                    if (_settings.AllowTyping) _keys.Send(KeyMapping.Press(k.VirtualKey, k.Modifiers));
                    break;
                case Wire.Ping: s.Send(Wire.Pong, f.Payload.Length <= 8 ? f.Payload : f.Payload[..8]); break;
                case Wire.Bye: return;
                default: break; // PONG and unknown types (forward compatibility)
            }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _listener?.Stop();
        DisconnectPhone();
        _listener?.Dispose();
        _cts.Dispose();
        _handshakes.Dispose();
    }
}

/// <summary>One TLS connection. Writes are serialised on a background queue so callers never block.</summary>
internal sealed class PhoneSession
{
    private readonly SslStream _ssl;
    private readonly CancellationTokenSource _cts;
    private readonly System.Threading.Channels.Channel<byte[]> _out =
        System.Threading.Channels.Channel.CreateBounded<byte[]>(new System.Threading.Channels.BoundedChannelOptions(256) { SingleReader = true });
    private int _closed;

    public PhoneSession(SslStream ssl, CancellationToken serverToken)
    {
        _ssl = ssl;
        _cts = CancellationTokenSource.CreateLinkedTokenSource(serverToken);
        _ = Task.Run(WriteLoopAsync, CancellationToken.None);
    }

    public string Name { get; set; } = "Phone";
    public bool Casting { get; set; }

    public void Send(byte type, ReadOnlySpan<byte> payload)
    {
        if (Volatile.Read(ref _closed) != 0) return;
        if (!_out.Writer.TryWrite(FrameCodec.Encode(type, payload))) AppLog.Info("session", "send queue full; message dropped");
    }

    public async Task<Frame> ReadAsync(TimeSpan timeout)
    {
        using var t = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
        t.CancelAfter(timeout);
        return await FrameCodec.ReadAsync(_ssl, t.Token).ConfigureAwait(false);
    }

    private async Task WriteLoopAsync()
    {
        try
        {
            await foreach (var frame in _out.Reader.ReadAllAsync(_cts.Token).ConfigureAwait(false))
            {
                await _ssl.WriteAsync(frame, _cts.Token).ConfigureAwait(false);
                if (frame[0] == Wire.Bye) break;
            }
            await _ssl.FlushAsync(_cts.Token).ConfigureAwait(false);
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or ObjectDisposedException or InvalidOperationException)
        {
            // connection gone
        }
        finally
        {
            Volatile.Write(ref _closed, 1);
            _cts.Cancel();
            await _ssl.DisposeAsync().ConfigureAwait(false);
            _cts.Dispose();
        }
    }

    public void Close(bool sendBye)
    {
        if (Interlocked.Exchange(ref _closed, 1) != 0) return;
        if (sendBye) _out.Writer.TryWrite(FrameCodec.Encode(Wire.Bye, []));
        _out.Writer.TryComplete();
        if (!sendBye) { try { _cts.Cancel(); } catch (ObjectDisposedException) { } }
    }
}
