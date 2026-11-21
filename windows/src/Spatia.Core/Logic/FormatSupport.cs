namespace Spatia.Core.Logic;

public enum FormatVerdict { Supported, OpusDecoder, IamfUnsupported, KnownUnsupported, NotAudio, Unknown }

/// <summary>Decides up front whether a file can be played, so the user gets a clear message instead of a silent failure.</summary>
public static class FormatSupport
{
    private static readonly HashSet<string> MediaFoundation = new(StringComparer.OrdinalIgnoreCase)
        { ".flac", ".mp3", ".aac", ".m4a", ".mp4", ".wav" };
    private static readonly HashSet<string> KnownUnsupported = new(StringComparer.OrdinalIgnoreCase)
        { ".dsf", ".dff", ".ape", ".wv", ".wma", ".aiff", ".aif", ".mka", ".ogg" };
    private static readonly HashSet<string> NotAudio = new(StringComparer.OrdinalIgnoreCase)
        { ".jpg", ".jpeg", ".png", ".gif", ".pdf", ".txt", ".exe", ".zip", ".doc", ".docx" };

    public static readonly string[] PickerExtensions = [".flac", ".mp3", ".aac", ".m4a", ".mp4", ".wav", ".opus", ".iamf"];

    public static FormatVerdict Classify(string path)
    {
        var ext = Path.GetExtension(path);
        if (ext.Equals(".opus", StringComparison.OrdinalIgnoreCase)) return FormatVerdict.OpusDecoder;
        if (ext.Equals(".iamf", StringComparison.OrdinalIgnoreCase)) return FormatVerdict.IamfUnsupported;
        if (MediaFoundation.Contains(ext)) return FormatVerdict.Supported;
        if (KnownUnsupported.Contains(ext)) return FormatVerdict.KnownUnsupported;
        if (NotAudio.Contains(ext)) return FormatVerdict.NotAudio;
        return FormatVerdict.Unknown;
    }
}
