using System.Diagnostics;
using System.Globalization;
using ScreenLink.Core.Protocol;

namespace ScreenLink.Core.Logic;

/// <summary>
/// Windows Defender Firewall rules for ScreenLink: inbound TCP 47420-47429 and UDP 47421, for ScreenLink.exe only, on
/// <b>private</b> networks only (never public Wi-Fi). Adding needs administrator rights, so the app relaunches itself
/// elevated with <c>--firewall-add</c>; <c>--firewall-remove</c> (run by the uninstaller) asks for elevation only if a
/// rule exists. Exit codes: 0 done, 1 failed, 2 cancelled by the user.
/// </summary>
public static class Firewall
{
    public const string AddArg = "--firewall-add";
    public const string RemoveArg = "--firewall-remove";
    public const string RemoveElevatedArg = "--firewall-remove-elevated";
    public const string TcpRule = "ScreenLink";
    public const string UdpRule = "ScreenLink discovery";

    private static string Netsh => Path.Combine(Environment.SystemDirectory, "netsh.exe");

    public static IReadOnlyList<string[]> AddCommands(string programPath)
    {
        var tcpPorts = string.Create(CultureInfo.InvariantCulture, $"{Wire.TcpPort}-{Wire.TcpPort + 9}");
        var udpPort = Wire.DiscoveryPort.ToString(CultureInfo.InvariantCulture);
        return
        [
            ["advfirewall", "firewall", "delete", "rule", $"name={TcpRule}"],
            ["advfirewall", "firewall", "delete", "rule", $"name={UdpRule}"],
            ["advfirewall", "firewall", "add", "rule", $"name={TcpRule}", "dir=in", "action=allow", $"program={programPath}",
             "enable=yes", "profile=private", "protocol=TCP", $"localport={tcpPorts}"],
            ["advfirewall", "firewall", "add", "rule", $"name={UdpRule}", "dir=in", "action=allow", $"program={programPath}",
             "enable=yes", "profile=private", "protocol=UDP", $"localport={udpPort}"],
        ];
    }

    public static IReadOnlyList<string[]> RemoveCommands() =>
    [
        ["advfirewall", "firewall", "delete", "rule", $"name={TcpRule}"],
        ["advfirewall", "firewall", "delete", "rule", $"name={UdpRule}"],
    ];

    /// <summary>Handles the firewall command-line modes; returns the exit code, or null if <paramref name="args"/> isn't one.</summary>
    public static int? TryRun(IReadOnlyList<string> args)
    {
        ArgumentNullException.ThrowIfNull(args);
        if (args.Count == 0) return null;
        var exe = Environment.ProcessPath ?? "";
        switch (args[0])
        {
            case AddArg:
                var cmds = AddCommands(exe);
                foreach (var c in cmds.Take(2)) RunNetsh(c); // remove old rules first (fails harmlessly when there are none)
                var ok = cmds.Skip(2).All(c => RunNetsh(c) == 0);
                AppLog.Info("firewall", ok ? "rules added" : "adding rules failed");
                return ok ? 0 : 1;
            case RemoveElevatedArg:
                foreach (var c in RemoveCommands()) RunNetsh(c);
                AppLog.Info("firewall", "rules removed");
                return 0;
            case RemoveArg:
                if (!RuleExists()) return 0;
                return RunElevated(RemoveElevatedArg);
            default:
                return null;
        }
    }

    /// <summary>Asks Windows to run ScreenLink elevated with <paramref name="arg"/> and waits. UAC shows the publisher and path.</summary>
    public static int RunElevated(string arg)
    {
        var exe = Environment.ProcessPath;
        if (exe is null) return 1;
        try
        {
            using var p = Process.Start(new ProcessStartInfo(exe, arg) { Verb = "runas", UseShellExecute = true, WorkingDirectory = AppContext.BaseDirectory });
            if (p is null) return 1;
            p.WaitForExit();
            return p.ExitCode;
        }
        catch (System.ComponentModel.Win32Exception e) when (e.NativeErrorCode == 1223) { return 2; } // user said No
        catch (System.ComponentModel.Win32Exception e) { AppLog.Error("firewall", "elevation failed: " + e.Message); return 1; }
    }

    public static bool RuleExists() => RunNetsh(["advfirewall", "firewall", "show", "rule", $"name={TcpRule}"]) == 0;

    private static int RunNetsh(string[] args)
    {
        try
        {
            var psi = new ProcessStartInfo(Netsh) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (var a in args) psi.ArgumentList.Add(a);
            using var p = Process.Start(psi);
            if (p is null) return 1;
            p.StandardOutput.ReadToEnd();
            p.StandardError.ReadToEnd();
            if (!p.WaitForExit(15_000)) { p.Kill(); return 1; }
            return p.ExitCode;
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException or IOException)
        {
            AppLog.Error("firewall", "netsh: " + e.Message);
            return 1;
        }
    }
}
