using System.Globalization;
using System.Text;

namespace Spatia.Core.Logic;

/// <summary>
/// Small diagnostic log (%LOCALAPPDATA%\Spatia\logs\spatia.log): app version, OS, startup stages and errors. It never
/// records file names, device names or settings, so it can be shared for support. Capped at 512 KB (one backup kept).
/// </summary>
public static class AppLog
{
    public const long MaxBytes = 512 * 1024;
    private static readonly object Gate = new();
    public static string FilePath { get; private set; } =
        Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData), "Spatia", "logs", "spatia.log");

    public static void Initialize(string? directory = null)
    {
        if (directory is not null) FilePath = Path.Combine(directory, "spatia.log");
        Write("INFO", "startup", $"Spatia {typeof(AppLog).Assembly.GetName().Version} on {System.Environment.OSVersion.VersionString}, {System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture}");
    }

    public static void Info(string stage, string message) => Write("INFO", stage, message);
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
