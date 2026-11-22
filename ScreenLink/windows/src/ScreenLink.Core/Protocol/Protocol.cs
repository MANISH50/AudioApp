using System.Buffers.Binary;
using System.Text;

namespace ScreenLink.Core.Protocol;

/// <summary>
/// ScreenLink wire protocol v1 (mirrored in ScreenLink/android/.../net/Protocol.kt; see ScreenLink/PROTOCOL.md).
/// Frame: <c>type:u8 | length:u32 big-endian | payload</c>. Payloads over <see cref="MaxPayload"/> are rejected.
/// </summary>
public static class Wire
{
    public const int Version = 1;
    public const int TcpPort = 47420;
    public const int DiscoveryPort = 47421;
    public const int MaxPayload = 4 * 1024 * 1024;
    public const int MaxTextBytes = 1024;
    public const int MaxNameBytes = 128;

    public const byte Hello = 0x01;
    public const byte PairConfirm = 0x02;
    public const byte PairResult = 0x03;
    public const byte VideoConfig = 0x10;
    public const byte VideoFrame = 0x11;
    public const byte RequestKeyframe = 0x12;
    public const byte CastState = 0x13;
    public const byte KeyText = 0x20;
    public const byte KeyPress = 0x21;
    public const byte ControlPointer = 0x40;
    public const byte ControlScroll = 0x41;
    public const byte ControlGlobal = 0x42;
    public const byte ControlText = 0x43;
    public const byte ControlKey = 0x44;
    public const byte ControlState = 0x45;
    public const byte Ping = 0x30;
    public const byte Pong = 0x31;
    public const byte Bye = 0x3F;

    public const byte ModCtrl = 1, ModAlt = 2, ModShift = 4, ModWin = 8;
}

public sealed class ProtocolException(string message) : IOException(message);

public readonly record struct Frame(byte Type, byte[] Payload);

public static class FrameCodec
{
    /// <summary>Reads one frame. Throws <see cref="EndOfStreamException"/> when the peer closed the connection.</summary>
    public static async Task<Frame> ReadAsync(Stream stream, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(stream);
        var header = new byte[5];
        await stream.ReadExactlyAsync(header, ct).ConfigureAwait(false);
        var length = BinaryPrimitives.ReadUInt32BigEndian(header.AsSpan(1));
        if (length > Wire.MaxPayload) throw new ProtocolException("frame too large");
        var payload = length == 0 ? [] : new byte[length];
        if (length > 0) await stream.ReadExactlyAsync(payload, ct).ConfigureAwait(false);
        return new Frame(header[0], payload);
    }

    public static byte[] Encode(byte type, ReadOnlySpan<byte> payload)
    {
        if (payload.Length > Wire.MaxPayload) throw new ArgumentException("payload too large", nameof(payload));
        var buf = new byte[5 + payload.Length];
        buf[0] = type;
        BinaryPrimitives.WriteUInt32BigEndian(buf.AsSpan(1), (uint)payload.Length);
        payload.CopyTo(buf.AsSpan(5));
        return buf;
    }
}

public readonly record struct HelloMessage(int Version, string Name);
public readonly record struct VideoConfigMessage(int Width, int Height, byte[] Csd);
public readonly record struct VideoFrameMessage(long PtsMicros, bool KeyFrame, ArraySegment<byte> Data);
public readonly record struct KeyPressMessage(ushort VirtualKey, byte Modifiers);

/// <summary>Builders and strict parsers for each payload. Every parser rejects malformed input with <see cref="ProtocolException"/>.</summary>
public static class Messages
{
    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    public static byte[] Hello(string name)
    {
        var n = StrictUtf8.GetBytes(TruncateUtf8(name ?? "", Wire.MaxNameBytes));
        var b = new byte[4 + n.Length];
        BinaryPrimitives.WriteUInt16BigEndian(b, Wire.Version);
        BinaryPrimitives.WriteUInt16BigEndian(b.AsSpan(2), (ushort)n.Length);
        n.CopyTo(b, 4);
        return b;
    }

    public static HelloMessage ParseHello(byte[] p)
    {
        ArgumentNullException.ThrowIfNull(p);
        if (p.Length < 4) throw new ProtocolException("short HELLO");
        var version = BinaryPrimitives.ReadUInt16BigEndian(p);
        var len = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(2));
        if (len > Wire.MaxNameBytes || 4 + len > p.Length) throw new ProtocolException("bad name length");
        return new HelloMessage(version, Decode(p.AsSpan(4, len)));
    }

    public static byte[] Flag(bool value) => [value ? (byte)1 : (byte)0];

    public static bool ParseFlag(byte[] p)
    {
        ArgumentNullException.ThrowIfNull(p);
        if (p.Length != 1) throw new ProtocolException("bad flag");
        return p[0] != 0;
    }

    public static VideoConfigMessage ParseVideoConfig(byte[] p)
    {
        ArgumentNullException.ThrowIfNull(p);
        if (p.Length < 5) throw new ProtocolException("short VIDEO_CONFIG");
        int w = BinaryPrimitives.ReadUInt16BigEndian(p), h = BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(2));
        if (w < 16 || h < 16 || w > 4096 || h > 4096) throw new ProtocolException("bad video size");
        return new VideoConfigMessage(w, h, p[4..]);
    }

    public static VideoFrameMessage ParseVideoFrame(byte[] p)
    {
        ArgumentNullException.ThrowIfNull(p);
        if (p.Length < 10) throw new ProtocolException("short VIDEO_FRAME");
        var pts = BinaryPrimitives.ReadInt64BigEndian(p);
        return new VideoFrameMessage(pts, (p[8] & 1) != 0, new ArraySegment<byte>(p, 9, p.Length - 9));
    }

    public static string ParseKeyText(byte[] p)
    {
        ArgumentNullException.ThrowIfNull(p);
        if (p.Length > Wire.MaxTextBytes) throw new ProtocolException("text too long");
        return Decode(p);
    }

    public static byte[] KeyPress(ushort vk, byte modifiers)
    {
        var b = new byte[3];
        BinaryPrimitives.WriteUInt16BigEndian(b, vk);
        b[2] = modifiers;
        return b;
    }

    public static KeyPressMessage ParseKeyPress(byte[] p)
    {
        ArgumentNullException.ThrowIfNull(p);
        if (p.Length != 3) throw new ProtocolException("bad KEY_PRESS");
        var vk = BinaryPrimitives.ReadUInt16BigEndian(p);
        return new KeyPressMessage(vk, p[2]);
    }

    public const byte PointerDown = 0, PointerMove = 1, PointerUp = 2, PointerCancel = 3;
    public const byte GlobalBack = 1, GlobalHome = 2, GlobalRecents = 3, GlobalNotifications = 4;
    public const byte KeyBackspace = 1, KeyEnter = 2;

    /// <summary>Laptop → phone pointer event; x/y are 0..65535 across the video picture.</summary>
    public static byte[] PointerEvent(byte action, ushort x, ushort y)
    {
        var b = new byte[5];
        b[0] = action;
        BinaryPrimitives.WriteUInt16BigEndian(b.AsSpan(1), x);
        BinaryPrimitives.WriteUInt16BigEndian(b.AsSpan(3), y);
        return b;
    }

    public static byte[] Scroll(ushort x, ushort y, short delta)
    {
        var b = new byte[6];
        BinaryPrimitives.WriteUInt16BigEndian(b, x);
        BinaryPrimitives.WriteUInt16BigEndian(b.AsSpan(2), y);
        BinaryPrimitives.WriteInt16BigEndian(b.AsSpan(4), delta);
        return b;
    }

    public static byte[] Text(string text)
    {
        var bytes = StrictUtf8.GetBytes(TruncateUtf8(text ?? "", Wire.MaxTextBytes));
        return bytes;
    }

    private static string Decode(ReadOnlySpan<byte> bytes)
    {
        try { return StrictUtf8.GetString(bytes); }
        catch (DecoderFallbackException) { throw new ProtocolException("invalid UTF-8"); }
    }

    /// <summary>Cuts <paramref name="s"/> to at most <paramref name="maxBytes"/> UTF-8 bytes without splitting a character.</summary>
    public static string TruncateUtf8(string s, int maxBytes)
    {
        ArgumentNullException.ThrowIfNull(s);
        if (Encoding.UTF8.GetByteCount(s) <= maxBytes) return s;
        var sb = new StringBuilder();
        var used = 0;
        foreach (var rune in s.EnumerateRunes())
        {
            if (used + rune.Utf8SequenceLength > maxBytes) break;
            sb.Append(rune.ToString());
            used += rune.Utf8SequenceLength;
        }
        return sb.ToString();
    }
}
