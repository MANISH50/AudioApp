using Spatia.Core.Logic;

namespace Spatia.Core.Apo;

/// <summary>
/// The tiny elevated helper: <c>Spatia.exe --apo-connect &lt;user SID&gt;</c> and <c>Spatia.exe --apo-disconnect</c>.
/// Handled before any UI starts; exit code 0 = done, 1 = failed, 2 = Equalizer APO not found / bad arguments,
/// 3 = disconnect without rights to edit config.txt (e.g. from the uninstaller): Spatia's own file was set to "no
/// processing" instead, so other apps sound unchanged.
/// </summary>
public static class ApoCommandLine
{
    public const string ConnectArg = "--apo-connect";
    public const string DisconnectArg = "--apo-disconnect";

    /// <summary>Returns the exit code if <paramref name="args"/> (without the program name) is an APO command, else null.</summary>
    public static int? TryRun(IReadOnlyList<string> args)
    {
        if (args.Count == 0 || (args[0] != ConnectArg && args[0] != DisconnectArg)) return null;
        var dir = ApoLocator.FindConfigDir();
        if (dir is null) { AppLog.Error("apo", "Equalizer APO config folder not found"); return 2; }
        try
        {
            if (args[0] == ConnectArg)
            {
                var sid = args.Count > 1 ? args[1] : null;
                if (sid is not null && !ApoInstaller.IsValidSid(sid)) { AppLog.Error("apo", "invalid SID argument"); return 2; }
                ApoInstaller.Connect(dir, sid);
                AppLog.Info("apo", "connected");
            }
            else
            {
                try { ApoInstaller.Disconnect(dir); }
                catch (UnauthorizedAccessException)
                {
                    ApoInstaller.Neutralize(dir);
                    AppLog.Info("apo", "no rights to edit config.txt; Spatia's settings set to no processing");
                    return 3;
                }
                AppLog.Info("apo", "disconnected");
            }
            return 0;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException or InvalidOperationException
                                   or System.Security.SecurityException)
        {
            AppLog.Error("apo", e.GetType().Name + ": " + e.Message);
            return 1;
        }
    }
}
