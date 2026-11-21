using Microsoft.Win32;

namespace Spatia.Core.Apo;

public enum ApoState { NotInstalled, InstalledNotConnected, Connected }

/// <param name="State">Overall state.</param>
/// <param name="ConfigDir">Equalizer APO's config folder (null if not installed).</param>
/// <param name="InstallDir">Equalizer APO's program folder (for Device Selector), if known.</param>
/// <param name="Devices">Playback devices Equalizer APO is installed on (best effort; may be empty when unknown).</param>
/// <param name="NeedsElevation">Connected, or connect needs administrator rights (config.txt not writable by us).</param>
public sealed record ApoStatus(ApoState State, string? ConfigDir, string? InstallDir, IReadOnlyList<string> Devices, bool NeedsElevation)
{
    public string? SpatiaDir => ConfigDir is null ? null : Path.Combine(ConfigDir, ApoInstaller.SpatiaFolder);
    public string? DeviceSelectorPath => InstallDir is null ? null : Path.Combine(InstallDir, "DeviceSelector.exe");
}

/// <summary>
/// Finds Equalizer APO (https://sourceforge.net/projects/equalizerapo/) and whether Spatia is connected to it.
/// Order: SPATIA_APO_DIR (tests/CI), registry HKLM\SOFTWARE\EqualizerAPO ConfigPath, default install folder.
/// </summary>
public static class ApoLocator
{
    public const string EnvOverride = "SPATIA_APO_DIR";
    public const string DownloadUrl = "https://sourceforge.net/projects/equalizerapo/";
    public static readonly string DefaultConfigDir = @"C:\Program Files\EqualizerAPO\config";

    /// <summary>Equalizer APO's COM class id; it appears in a device's FxProperties when APO is installed on it.</summary>
    private const string ApoClsid = "{EACD2258-FCAC-4FF4-B36D-419E924A6D79}";

    public static ApoStatus Detect()
    {
        var (configDir, installDir) = FindDirs();
        if (configDir is null || !File.Exists(Path.Combine(configDir, ApoInstaller.ConfigFile)))
            return new ApoStatus(ApoState.NotInstalled, null, null, [], false);
        var devices = InstalledDevices();
        var included = ApoInstaller.HasInclude(configDir);
        var writable = IsWritable(Path.Combine(configDir, ApoInstaller.SpatiaFolder));
        var state = included && writable ? ApoState.Connected : ApoState.InstalledNotConnected;
        var needsElevation = !IsWritableFile(Path.Combine(configDir, ApoInstaller.ConfigFile));
        return new ApoStatus(state, configDir, installDir, devices, needsElevation);
    }

    /// <summary>Config folder only (used by the elevated connect/disconnect helper).</summary>
    public static string? FindConfigDir()
    {
        var (dir, _) = FindDirs();
        return dir is not null && File.Exists(Path.Combine(dir, ApoInstaller.ConfigFile)) ? dir : null;
    }

    private static (string? Config, string? Install) FindDirs()
    {
        var env = System.Environment.GetEnvironmentVariable(EnvOverride);
        if (!string.IsNullOrWhiteSpace(env)) return (Path.GetFullPath(env), null);
        if (!OperatingSystem.IsWindows()) return (null, null);
        string? config = null, install = null;
        try
        {
            using var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
            using var key = hklm.OpenSubKey(@"SOFTWARE\EqualizerAPO");
            config = key?.GetValue("ConfigPath") as string;
            install = key?.GetValue("InstallPath") as string;
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException) { }
        if (string.IsNullOrWhiteSpace(config) && Directory.Exists(DefaultConfigDir)) config = DefaultConfigDir;
        if (string.IsNullOrWhiteSpace(install) && config is not null) install = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(config));
        return (string.IsNullOrWhiteSpace(config) ? null : config, install);
    }

    /// <summary>
    /// Best effort: playback endpoints whose FxProperties reference Equalizer APO. Returns an empty list when unknown
    /// (the UI then reminds the user to tick devices in Equalizer APO's Device Selector).
    /// </summary>
    public static IReadOnlyList<string> InstalledDevices()
    {
        if (!OperatingSystem.IsWindows() || System.Environment.GetEnvironmentVariable(EnvOverride) is { Length: > 0 }) return [];
        var result = new List<string>();
        try
        {
            using var hklm = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
            using var render = hklm.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render");
            if (render is null) return result;
            foreach (var id in render.GetSubKeyNames())
            {
                try
                {
                    using var dev = render.OpenSubKey(id);
                    if (dev?.GetValue("DeviceState") is int st && st != 1) continue; // active endpoints only
                    using var fx = render.OpenSubKey(id + @"\FxProperties");
                    if (fx is null) continue;
                    var hasApo = false;
                    foreach (var n in fx.GetValueNames()) hasApo |= ValueMentions(fx.GetValue(n), ApoClsid);
                    if (!hasApo) continue;
                    using var props = render.OpenSubKey(id + @"\Properties");
                    var name = props?.GetValue("{a45c254e-df1c-4efd-8020-67d146a850e0},2") as string;
                    var adapter = props?.GetValue("{b3f8fa53-0004-438e-9003-51a46e139bfc},6") as string;
                    result.Add(name is null ? id : adapter is null ? name : $"{name} ({adapter})");
                }
                catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException) { }
            }
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException) { }
        return result;
    }

    private static bool ValueMentions(object? v, string clsid) => v switch
    {
        string s => s.Contains(clsid, StringComparison.OrdinalIgnoreCase),
        string[] a => a.Any(s => s.Contains(clsid, StringComparison.OrdinalIgnoreCase)),
        _ => false,
    };

    /// <summary>
    /// Whether we may write in <paramref name="dir"/>. Opens spatia.txt for writing without changing it (so Equalizer
    /// APO's change watcher isn't triggered on every check); only probes with a temporary file when it doesn't exist.
    /// </summary>
    internal static bool IsWritable(string dir)
    {
        try
        {
            if (!Directory.Exists(dir)) return false;
            var ours = Path.Combine(dir, ApoInstaller.SpatiaConfigFile);
            if (File.Exists(ours)) return IsWritableFile(ours);
            var probe = Path.Combine(dir, ".spatia-probe-" + Guid.NewGuid().ToString("N"));
            File.WriteAllBytes(probe, []);
            File.Delete(probe);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return false; }
    }

    private static bool IsWritableFile(string path)
    {
        try
        {
            using var _ = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return false; }
    }
}
