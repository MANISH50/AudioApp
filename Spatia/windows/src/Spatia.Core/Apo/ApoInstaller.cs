using System.Runtime.Versioning;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;

namespace Spatia.Core.Apo;

/// <summary>
/// Connects Spatia to Equalizer APO: creates config\Spatia\, lets the current user (only) write there, backs up
/// config.txt once and adds one <see cref="IncludeLine"/>. Disconnect removes that line again. Both are idempotent.
/// Writing config.txt usually needs administrator rights, so the app runs this in a short elevated helper process
/// (Spatia.exe --apo-connect &lt;SID&gt;); after that every settings change is written by the normal, unelevated app.
/// </summary>
public static class ApoInstaller
{
    public const string ConfigFile = "config.txt";
    public const string SpatiaFolder = "Spatia";
    public const string SpatiaConfigFile = "spatia.txt";
    public const string BackupFile = "config.txt.spatia-backup";
    public const string IncludeLine = @"Include: Spatia\spatia.txt";
    private const long MaxConfigBytes = 4 * 1024 * 1024;

    public static bool HasInclude(string configDir)
    {
        var path = Path.Combine(configDir, ConfigFile);
        try { return File.Exists(path) && ReadLines(path).Any(IsOurLine); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return false; }
    }

    /// <summary>
    /// Connects. <paramref name="userSid"/>: the user who gets Modify rights on config\Spatia (Windows only; null skips
    /// the ACL change, e.g. when the folder is already writable). Throws on I/O or permission errors.
    /// </summary>
    public static void Connect(string configDir, string? userSid)
    {
        var config = Path.Combine(configDir, ConfigFile);
        if (!File.Exists(config)) throw new FileNotFoundException("Equalizer APO's config.txt was not found.", config);
        var spatiaDir = Path.Combine(configDir, SpatiaFolder);
        Directory.CreateDirectory(spatiaDir);
        if (userSid is not null && OperatingSystem.IsWindows()) GrantModify(spatiaDir, userSid);
        var ours = Path.Combine(spatiaDir, SpatiaConfigFile);
        if (!File.Exists(ours)) WriteAtomic(ours, ApoConfigText.Header + "\n# Not configured yet.\n");

        var backup = Path.Combine(configDir, BackupFile);
        if (!File.Exists(backup)) File.Copy(config, backup);

        var text = ReadBounded(config, out var bom);
        if (SplitLines(text).Any(IsOurLine)) return;
        var nl = text.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
        var sb = new StringBuilder(text);
        if (text.Length > 0 && !text.EndsWith('\n')) sb.Append(nl);
        sb.Append(IncludeLine).Append(nl);
        WriteConfig(config, sb.ToString(), bom);
    }

    /// <summary>Removes Spatia's include line (every copy of it). Leaves the rest of config.txt untouched.</summary>
    public static void Disconnect(string configDir)
    {
        var config = Path.Combine(configDir, ConfigFile);
        if (!File.Exists(config)) return;
        var text = ReadBounded(config, out var bom);
        var lines = SplitLines(text);
        if (!lines.Any(IsOurLine)) return;
        var nl = text.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
        var kept = lines.Where(l => !IsOurLine(l)).ToList();
        var result = string.Join(nl, kept);
        if (text.EndsWith('\n') && result.Length > 0 && !result.EndsWith('\n')) result += nl;
        WriteConfig(config, result, bom);
    }

    /// <summary>
    /// Makes Spatia's part a no-op without touching config.txt (works with just the user's rights on config\Spatia):
    /// spatia.txt becomes a comment and rendered impulse responses are deleted.
    /// </summary>
    public static void Neutralize(string configDir)
    {
        var dir = Path.Combine(configDir, SpatiaFolder);
        if (!Directory.Exists(dir)) return;
        WriteAtomic(Path.Combine(dir, SpatiaConfigFile), ApoConfigText.Header + "\n# Spatia is disconnected: no processing.\n");
        foreach (var f in Directory.EnumerateFiles(dir, ApoConfigWriter.ImpulsePrefix + "*.wav"))
        {
            try { File.Delete(f); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { /* in use; harmless */ }
        }
    }

    /// <summary>A valid Windows SID string (S-1-...), checked with SecurityIdentifier on Windows.</summary>
    public static bool IsValidSid(string? sid)
    {
        if (string.IsNullOrEmpty(sid) || sid.Length > 184 || !sid.StartsWith("S-1-", StringComparison.Ordinal)) return false;
        if (!OperatingSystem.IsWindows()) return sid.Skip(4).All(c => char.IsAsciiDigit(c) || c == '-');
        try { return new SecurityIdentifier(sid).IsAccountSid(); }
        catch (ArgumentException) { return false; }
    }

    [SupportedOSPlatform("windows")]
    private static void GrantModify(string dir, string sid)
    {
        if (!IsValidSid(sid)) throw new ArgumentException("Invalid user SID.", nameof(sid));
        var info = new DirectoryInfo(dir);
        var acl = info.GetAccessControl();
        acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid), FileSystemRights.Modify,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        info.SetAccessControl(acl);
    }

    private static bool IsOurLine(string line) => line.Trim().Equals(IncludeLine, StringComparison.OrdinalIgnoreCase);

    private static List<string> SplitLines(string text)
    {
        var lines = text.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n').ToList();
        if (lines.Count > 0 && lines[^1].Length == 0) lines.RemoveAt(lines.Count - 1);
        return lines;
    }

    private static List<string> ReadLines(string path) => SplitLines(ReadBounded(path));

    private static string ReadBounded(string path) => ReadBounded(path, out _);

    private static string ReadBounded(string path, out bool hasBom)
    {
        if (new FileInfo(path).Length > MaxConfigBytes) throw new IOException("config.txt is unexpectedly large.");
        var bytes = File.ReadAllBytes(path);
        hasBom = bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;
        // Latin-1 maps every byte to one char and back, so other lines survive byte for byte whatever their encoding.
        return Encoding.Latin1.GetString(bytes, hasBom ? 3 : 0, bytes.Length - (hasBom ? 3 : 0));
    }

    /// <summary>Writes config.txt back byte for byte, keeping its UTF-8 byte-order mark (or lack of one).</summary>
    private static void WriteConfig(string path, string text, bool bom) =>
        WriteAtomic(path, (bom ? new byte[] { 0xEF, 0xBB, 0xBF } : []).Concat(Encoding.Latin1.GetBytes(text)).ToArray());

    /// <summary>Temp file in the same folder, then replace, so Equalizer APO never reads a half-written file.</summary>
    public static void WriteAtomic(string path, string text) => WriteAtomic(path, new UTF8Encoding(false).GetBytes(text));

    public static void WriteAtomic(string path, byte[] bytes)
    {
        var tmp = path + ".tmp-" + Guid.NewGuid().ToString("N")[..8];
        try
        {
            File.WriteAllBytes(tmp, bytes);
            File.Move(tmp, path, overwrite: true);
        }
        finally { if (File.Exists(tmp)) File.Delete(tmp); }
    }
}
