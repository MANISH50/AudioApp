namespace ScreenLink.Core.Control;

/// <summary>
/// Maps a point in the viewer (which shows the phone picture scaled to fit, with black bars) to the phone's 0..65535
/// picture coordinates. Points on the black bars map to nothing.
/// </summary>
public static class PointerMapper
{
    public readonly record struct Rect(double X, double Y, double Width, double Height);

    /// <summary>Where a <paramref name="videoW"/>×<paramref name="videoH"/> picture sits when fitted (uniform) into the viewer.</summary>
    public static Rect Fit(double viewW, double viewH, int videoW, int videoH)
    {
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return default;
        var scale = Math.Min(viewW / videoW, viewH / videoH);
        double w = videoW * scale, h = videoH * scale;
        return new Rect((viewW - w) / 2, (viewH - h) / 2, w, h);
    }

    /// <summary>The 0..65535 coordinates of a viewer point, or null when it is outside the picture.</summary>
    public static (ushort X, ushort Y)? Map(double px, double py, double viewW, double viewH, int videoW, int videoH, bool clamp = false)
    {
        var r = Fit(viewW, viewH, videoW, videoH);
        if (r.Width <= 0) return null;
        var fx = (px - r.X) / r.Width;
        var fy = (py - r.Y) / r.Height;
        if (!clamp && (fx < 0 || fx > 1 || fy < 0 || fy > 1)) return null;
        fx = Math.Clamp(fx, 0, 1);
        fy = Math.Clamp(fy, 0, 1);
        return ((ushort)Math.Round(fx * 65535), (ushort)Math.Round(fy * 65535));
    }
}
