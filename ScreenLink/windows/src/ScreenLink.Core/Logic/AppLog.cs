using System.Globalization;
using System.Text;

namespace ScreenLink.Core.Logic;

/// <summary>
/// Small diagnostic log (%LOCALAPPDATA%\ScreenLink\logs\screenlink.log): app version, OS, startup stages and errors. It never
/// records typed text, screen content or addresses, so it can be shared for support. Capped at 512 KB (one backup kept).
/// </summary>
public static class AppLog
{
    public const long MaxBytes = 512 * 1024;
    private static readonly object Gate = new();
    public static string FilePath { get; private set; } =
        Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData), "ScreenLink", "logs", "screenlink.log");

    public static void Initialize(string? directory = null)
    {
        if (directory is not null) FilePath = Path.Combine(directory, "screenlink.log");
        Write("INFO", "startup", $"ScreenLink {typeof(AppLog).Assembly.GetName().Version} on {System.Environment.OSVersion.VersionString}, {System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture}");
    }

    public static void Info(string stage, string message) => Write("INFO", stage, message);

    private static int _firstChance;

    /// <summary>
    /// Diagnostics mode only (SCREENLINK_DIAGNOSTICS=1, set by CI): records every exception as it is thrown, even ones the
    /// UI framework later turns into a native crash that bypasses normal handlers. Capped at 200 entries.
    /// </summary>
    public static void FirstChance(Exception ex)
    {
        if (Interlocked.Increment(ref _firstChance) > 200) return;
        Write("DEBUG", "first-chance", string.Create(CultureInfo.InvariantCulture,
            $"{ex.GetType().FullName} HRESULT 0x{ex.HResult:X8}: {ex.Message}{System.Environment.NewLine}{ex.StackTrace}"));
    }

    public static bool DiagnosticsEnabled => System.Environment.GetEnvironmentVariable("SCREENLINK_DIAGNOSTICS") == "1";
    public static void Error(string stage, string message) => Write("ERROR", stage, message);
    /// <summary>Logs the exception with its HRESULT and inner exceptions (WinRT errors often hide the cause there).</summary>
    public static void Error(string stage, Exception ex) =>
        Write("ERROR", stage, string.Create(CultureInfo.InvariantCulture, $"HRESULT 0x{ex.HResult:X8} {ex}"));

    private static void Write(string level, string stage, string message)
    {
        try
        {
            lock (Gate)
            {
                var dir = Path.GetDirectoryName(FilePath)!;
                Directory.CreateDirectory(dir);
                var info = new FileInfo(FilePath);
                if (info.Exists && info.Length > MaxBytes) File.Move(FilePath, FilePath + ".old", overwrite: true);
                var line = string.Create(CultureInfo.InvariantCulture,
                    $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz} {level,-5} [{stage}] {message}{System.Environment.NewLine}");
                File.AppendAllText(FilePath, line, Encoding.UTF8);
            }
        }
        catch (Exception) { /* logging must never crash the app */ }
    }
}
