using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using ScreenLink.Core.Logic;
using ScreenLink.Core.Protocol;

namespace ScreenLink.Core.Net;

/// <summary>
/// Answers phones looking for laptops: "SCREENLINK?1" on UDP 47421 gets "SCREENLINK!1\n&lt;tcp port&gt;\n&lt;fingerprint&gt;\n&lt;name&gt;".
/// Rate-limited, and it only ever sends that fixed public reply.
/// </summary>
public sealed class DiscoveryResponder : IDisposable
{
    public const string Query = "SCREENLINK?1";
    private readonly UdpClient _udp;
    private readonly CancellationTokenSource _cts = new();
    private readonly byte[] _reply;
    private long _windowStart = Stopwatch.GetTimestamp();
    private int _repliesInWindow;

    public DiscoveryResponder(int tcpPort, string fingerprintHex, string laptopName, int port = Wire.DiscoveryPort)
    {
        _reply = BuildReply(tcpPort, fingerprintHex, laptopName);
        _udp = new UdpClient(new IPEndPoint(IPAddress.Any, port));
        LocalPort = ((IPEndPoint)_udp.Client.LocalEndPoint!).Port;
        _ = Task.Run(LoopAsync, CancellationToken.None);
    }

    public int LocalPort { get; }

    public static byte[] BuildReply(int tcpPort, string fingerprintHex, string name)
    {
        var clean = new string([.. (name ?? "").Where(c => !char.IsControl(c))]);
        if (clean.Length > 64) clean = clean[..64];
        return Encoding.UTF8.GetBytes($"SCREENLINK!1\n{tcpPort.ToString(System.Globalization.CultureInfo.InvariantCulture)}\n{fingerprintHex}\n{clean}");
    }

    private bool Allow()
    {
        var now = Stopwatch.GetTimestamp();
        if (Stopwatch.GetElapsedTime(_windowStart, now) > TimeSpan.FromSeconds(1)) { _windowStart = now; _repliesInWindow = 0; }
        return ++_repliesInWindow <= 20;
    }

    private async Task LoopAsync()
    {
        var query = Encoding.ASCII.GetBytes(Query);
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var r = await _udp.ReceiveAsync(_cts.Token).ConfigureAwait(false);
                if (r.Buffer.AsSpan().SequenceEqual(query) && Allow())
                {
                    await _udp.SendAsync(_reply, r.RemoteEndPoint, _cts.Token).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException e) { AppLog.Info("discovery", "socket: " + e.SocketErrorCode); }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _udp.Dispose();
        _cts.Dispose();
    }
}
