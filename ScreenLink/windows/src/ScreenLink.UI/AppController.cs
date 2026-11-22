using System.Net.NetworkInformation;
using System.Net.Sockets;
using Microsoft.UI.Dispatching;
using ScreenLink.Core.Logic;
using ScreenLink.Core.Net;
using ScreenLink.Core.Security;
using ScreenLink.Core.Settings;
using ScreenLink.UI.Platform;

namespace ScreenLink.UI;

/// <summary>
/// Owns the laptop side: identity, paired phones, settings, the TLS server and discovery. Server events arrive on
/// network threads; the UI-facing ones are re-raised on the UI thread. Video goes straight to the player.
/// </summary>
public sealed class AppController : IDisposable
{
    private readonly DispatcherQueue _dq;
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2? _cert;
    private readonly DiscoveryResponder? _discovery;

    public static string DataDir { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ScreenLink");

    public AppSettings Settings { get; }
    public PairedPhones Paired { get; }
    public LinkServer? Server { get; }
    public string LaptopName { get; } = Environment.MachineName;
    /// <summary>Why the server couldn't start, if it couldn't.</summary>
    public string? StartError { get; }

    public event Action<PairingRequest>? PairingRequested;
    public event Action<PairingRequest>? PairingEnded;
    public event Action<string>? PhoneConnected;
    public event Action? PhoneDisconnected;
    public event Action<bool>? CastStateChanged;
    public event Action<bool>? ControlStateChanged;

    public AppController(DispatcherQueue dq)
    {
        _dq = dq;
        Settings = AppSettings.Load(DataDir);
        Paired = new PairedPhones(DataDir);
        try
        {
            _cert = Identity.LoadOrCreate(DataDir, Dpapi.Protect, Dpapi.Unprotect);
            Server = new LinkServer(_cert, Paired, Settings, new KeyInjector(), LaptopName);
            Server.PairingRequested += r => _dq.TryEnqueue(() => PairingRequested?.Invoke(r));
            Server.PairingEnded += r => _dq.TryEnqueue(() => PairingEnded?.Invoke(r));
            Server.PhoneConnected += n => _dq.TryEnqueue(() => PhoneConnected?.Invoke(n));
            Server.PhoneDisconnected += () => _dq.TryEnqueue(() => PhoneDisconnected?.Invoke());
            Server.CastStateChanged += c => _dq.TryEnqueue(() => CastStateChanged?.Invoke(c));
            Server.ControlStateChanged += c => _dq.TryEnqueue(() => ControlStateChanged?.Invoke(c));
            Server.Start();
        }
        catch (Exception e) when (e is IOException or SocketException or UnauthorizedAccessException
                                   or System.Security.Cryptography.CryptographicException)
        {
            AppLog.Error("startup", e);
            StartError = e.Message;
            return;
        }
        try { _discovery = new DiscoveryResponder(Server.Port, Server.FingerprintHex, LaptopName); }
        catch (SocketException e)
        {
            // Phones can still connect by address.
            AppLog.Error("discovery", "not available: " + e.SocketErrorCode);
        }
    }

    /// <summary>This laptop's IPv4 addresses on active, non-virtual-looking adapters (the ones with a gateway first).</summary>
    public static IReadOnlyList<string> Addresses()
    {
        try
        {
            return [.. NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .Select(n => (n, p: n.GetIPProperties()))
                .OrderByDescending(x => x.p.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork))
                .SelectMany(x => x.p.UnicastAddresses)
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !System.Net.IPAddress.IsLoopback(a.Address)
                            && !a.Address.ToString().StartsWith("169.254.", StringComparison.Ordinal))
                .Select(a => a.Address.ToString())
                .Distinct()];
        }
        catch (NetworkInformationException) { return []; }
    }

    public string FingerprintShort => Server is null ? "" :
        string.Join(' ', Enumerable.Range(0, 4).Select(i => Server.FingerprintHex.Substring(i * 4, 4).ToUpperInvariant()));

    public void Dispose()
    {
        _discovery?.Dispose();
        Server?.Dispose();
        _cert?.Dispose();
    }
}
