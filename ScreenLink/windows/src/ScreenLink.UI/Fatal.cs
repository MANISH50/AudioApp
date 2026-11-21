using System.Runtime.InteropServices;
using ScreenLink.Core.Logic;

namespace ScreenLink.UI;

/// <summary>
/// Last-resort error reporting that works even when the WinUI window couldn't be created: a native Windows message
/// box plus the log. ScreenLink never fails silently.
/// </summary>
public static partial class Fatal
{
    private const uint MbIconError = 0x10, MbOk = 0x0, MbSetForeground = 0x10000, MbTopMost = 0x40000;

    [LibraryImport("user32.dll", EntryPoint = "MessageBoxW", StringMarshalling = StringMarshalling.Utf16)]
    private static partial int MessageBoxW(nint hWnd, string text, string caption, uint type);

    /// <summary>Logs the failure, tells the user what happened and where the log is, then exits with code 1.</summary>
    public static void ReportAndExit(string stage, Exception ex)
    {
        AppLog.Error(stage, ex);
        Show($"ScreenLink couldn't start ({stage}).\n\n{ex.GetType().Name}: {ex.Message}\n\nDetails were saved to:\n{AppLog.FilePath}");
        System.Environment.Exit(1);
    }

    public static void Show(string message)
    {
        // Unattended runs (CI) must not block on a dialog; the log and exit code carry the failure.
        if (System.Environment.GetEnvironmentVariable("SCREENLINK_NO_DIALOGS") == "1") return;
        try { _ = MessageBoxW(0, message, "ScreenLink", MbOk | MbIconError | MbSetForeground | MbTopMost); }
        catch (Exception) { /* nothing else we can do */ }
    }
}
