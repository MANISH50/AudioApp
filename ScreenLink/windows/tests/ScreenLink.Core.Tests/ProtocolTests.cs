using System.Buffers.Binary;
using ScreenLink.Core.Net;
using ScreenLink.Core.Protocol;
using Xunit;

namespace ScreenLink.Core.Tests;

public class ProtocolTests
{
    [Fact]
    public async Task Frames_round_trip()
    {
        using var ms = new MemoryStream();
        ms.Write(FrameCodec.Encode(Wire.Hello, Messages.Hello("Laptop ✓")));
        ms.Write(FrameCodec.Encode(Wire.KeyText, "héllo 👋"u8));
        ms.Position = 0;
        var f = await FrameCodec.ReadAsync(ms, default);
        Assert.Equal(Wire.Hello, f.Type);
        Assert.Equal(new HelloMessage(1, "Laptop ✓"), Messages.ParseHello(f.Payload));
        Assert.Equal("héllo 👋", Messages.ParseKeyText((await FrameCodec.ReadAsync(ms, default)).Payload));
        await Assert.ThrowsAsync<EndOfStreamException>(() => FrameCodec.ReadAsync(ms, default));
    }

    [Fact]
    public void Wire_format_matches_android()
    {
        // Same bytes as ProtocolTest.wire_format_is_type_length_payload on Android.
        Assert.Equal(new byte[] { 0x21, 0, 0, 0, 3, 0, 0x0D, 1 }, FrameCodec.Encode(Wire.KeyPress, Messages.KeyPress(0x0D, Wire.ModCtrl)));
    }

    [Fact]
    public async Task Oversize_frame_rejected()
    {
        var header = new byte[5];
        header[0] = Wire.VideoFrame;
        BinaryPrimitives.WriteUInt32BigEndian(header.AsSpan(1), Wire.MaxPayload + 1);
        await Assert.ThrowsAsync<ProtocolException>(() => FrameCodec.ReadAsync(new MemoryStream(header), default));
    }

    [Fact]
    public void Malformed_payloads_rejected()
    {
        Assert.Throws<ProtocolException>(() => Messages.ParseHello([0, 1, 0x7F, 0x7F]));
        Assert.Throws<ProtocolException>(() => Messages.ParseKeyText([0xFF, 0xFE]));
        Assert.Throws<ProtocolException>(() => Messages.ParseKeyText(new byte[Wire.MaxTextBytes + 1]));
        Assert.Throws<ProtocolException>(() => Messages.ParseKeyPress([1, 2]));
        Assert.Throws<ProtocolException>(() => Messages.ParseVideoConfig([0, 1, 0, 1, 0]));
        Assert.Throws<ProtocolException>(() => Messages.ParseFlag([]));
    }

    [Fact]
    public void Video_messages_parse()
    {
        var cfg = Messages.ParseVideoConfig([0x02, 0x40, 0x05, 0x00, 0, 0, 0, 1, 0x67]);
        Assert.Equal(576, cfg.Width);
        Assert.Equal(1280, cfg.Height);
        Assert.Equal(5, cfg.Csd.Length);
        var p = new byte[12];
        BinaryPrimitives.WriteInt64BigEndian(p, 1234);
        p[8] = 1;
        var fr = Messages.ParseVideoFrame(p);
        Assert.True(fr.KeyFrame);
        Assert.Equal(1234, fr.PtsMicros);
        Assert.Equal(3, fr.Data.Count);
    }

    [Fact]
    public void Truncate_never_splits_characters()
    {
        Assert.Equal("ab", Messages.TruncateUtf8("ab👋", 5));
        Assert.Equal("ab👋", Messages.TruncateUtf8("ab👋", 6));
    }

    [Fact]
    public void Discovery_reply_is_clean()
    {
        var r = System.Text.Encoding.UTF8.GetString(DiscoveryResponder.BuildReply(47420, new string('a', 64), "My\nLaptop"));
        Assert.Equal($"SCREENLINK!1\n47420\n{new string('a', 64)}\nMyLaptop", r);
    }
}

public class KeyMappingTests
{
    [Fact]
    public void Text_uses_unicode_and_enter()
    {
        var s = KeyMapping.Text("a\n👋");
        Assert.Equal(2 + 2 + 4, s.Count); // a down/up, Enter down/up, two surrogates down/up
        Assert.True(s[0].IsUnicode && s[0].Unicode == 'a' && !s[0].Up);
        Assert.Equal(KeyMapping.Return, s[2].VirtualKey);
    }

    [Fact]
    public void Control_characters_are_never_injected()
    {
        Assert.Empty(KeyMapping.Text("\u0007\u001b\r"));
    }

    [Fact]
    public void Shortcut_presses_and_releases_modifiers_in_order()
    {
        var s = KeyMapping.Press('C', Wire.ModCtrl | Wire.ModShift);
        Assert.Equal(new ushort[] { KeyMapping.Control, KeyMapping.Shift, 'C', 'C', KeyMapping.Shift, KeyMapping.Control }, s.Select(k => k.VirtualKey));
        Assert.Equal(new[] { false, false, false, true, true, true }, s.Select(k => k.Up));
    }

    [Fact]
    public void Arrows_are_extended_keys()
    {
        Assert.All(KeyMapping.Press(KeyMapping.Left, 0), k => Assert.True(k.Extended));
    }

    [Theory]
    [InlineData(0x5B)] // Windows key alone
    [InlineData(0x2C)] // Print Screen
    [InlineData(0x91)] // Scroll Lock
    [InlineData(0xFFFF)]
    public void Keys_outside_allow_list_are_ignored(ushort vk) => Assert.Empty(KeyMapping.Press(vk, 0));

    [Fact]
    public void Bad_modifier_bits_are_ignored() => Assert.Empty(KeyMapping.Press('A', 0x10));
}
