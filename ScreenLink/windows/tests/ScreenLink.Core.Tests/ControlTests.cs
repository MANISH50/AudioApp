using ScreenLink.Core.Control;
using ScreenLink.Core.Protocol;
using Xunit;

namespace ScreenLink.Core.Tests;

public class ControlTests
{
    [Fact]
    public void Pointer_layout_matches_android()
    {
        // Same bytes as ControlProtocolTest.pointer_layout_matches_windows on Android.
        Assert.Equal(new byte[] { 0x40, 0, 0, 0, 5, 2, 0x80, 0, 0xFF, 0xFF },
            FrameCodec.Encode(Wire.ControlPointer, Messages.PointerEvent(Messages.PointerUp, 0x8000, 0xFFFF)));
    }

    [Fact]
    public void Scroll_is_signed_big_endian() =>
        Assert.Equal(new byte[] { 0, 10, 0, 20, 0xFF, 0x10 }, Messages.Scroll(10, 20, -240));

    [Fact]
    public void Portrait_phone_in_landscape_window_has_side_bars()
    {
        var r = PointerMapper.Fit(1000, 800, 576, 1280);
        Assert.Equal(360, r.Width, 3);
        Assert.Equal(800, r.Height, 3);
        Assert.Equal(320, r.X, 3);
        Assert.Null(PointerMapper.Map(100, 400, 1000, 800, 576, 1280));           // on the black bar
        Assert.Equal(((ushort)32768, (ushort)32768), PointerMapper.Map(500, 400, 1000, 800, 576, 1280));
        Assert.Equal(((ushort)0, (ushort)0), PointerMapper.Map(320, 0, 1000, 800, 576, 1280));
        Assert.Equal(((ushort)65535, (ushort)65535), PointerMapper.Map(680, 800, 1000, 800, 576, 1280));
    }

    [Fact]
    public void Dragging_off_the_picture_clamps_to_its_edge() =>
        Assert.Equal(((ushort)0, (ushort)32768), PointerMapper.Map(10, 400, 1000, 800, 576, 1280, clamp: true));

    [Fact]
    public void No_video_maps_nothing() => Assert.Null(PointerMapper.Map(1, 1, 100, 100, 0, 0));
}
