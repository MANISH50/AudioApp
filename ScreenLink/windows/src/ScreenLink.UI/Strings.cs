namespace ScreenLink.UI;

/// <summary>User-visible text (English). Kept in one place so it can be translated later.</summary>
public static class Strings
{
    public static string F(string format, params object?[] args) =>
        string.Format(System.Globalization.CultureInfo.CurrentCulture, format, args);

    public const string AppTitle = "ScreenLink";
    public const string StatusHeading = "Status";
    public const string StatusWaiting = "Waiting for your phone.";
    public const string StatusConnected = "Connected to {0}.";
    public const string StatusCasting = "Showing {0}'s screen.";
    public const string StatusUnavailable = "ScreenLink can't accept connections (see the message above).";
    public const string Disconnect = "Disconnect phone";
    public const string ConnectHeading = "Connect your phone";
    public const string ConnectHelp = "Open ScreenLink on your phone (same Wi-Fi) and tap Search, or type this address:";
    public const string AddressLabel = "This laptop's address";
    public const string NoNetwork = "No network connection.";
    public const string LaptopIdentity = "Laptop: {0} · ID {1}";
    public const string KeyboardHeading = "Phone keyboard";
    public const string AllowTyping = "Allow typing from the phone";
    public const string KeyboardHelp = "Text typed on the phone goes to the window that has focus. Windows doesn't allow typing into apps that run as administrator.";
    public const string PairedHeading = "Paired phones";
    public const string NoPaired = "No phones paired yet.";
    public const string Remove = "Remove";
    public const string TroubleHeading = "Phone can't find this laptop?";
    public const string FirewallHelp = "Both devices must be on the same Wi-Fi, and Windows must treat it as a private network. If Windows Firewall didn't ask about ScreenLink, allow it here (asks for administrator approval; private networks only).";
    public const string FirewallButton = "Allow ScreenLink on private networks";
    public const string FirewallDone = "ScreenLink is allowed on private networks.";
    public const string FirewallCancelled = "Nothing was changed.";
    public const string FirewallFailed = "Couldn't change the firewall. See the log for details.";
    public const string LogLocation = "Log: {0}";
    public const string PhoneScreen = "Phone screen";
    public const string FullScreen = "Full screen (F11)";
    public const string OverlayWaiting = "Your phone's screen will appear here.\nConnect your phone to start.";
    public const string OverlayNotCasting = "Phone connected. Tap Start casting on the phone to show its screen.";
    public const string PairTitle = "Pair with {0}?";
    public const string PairBody = "Allow only if your phone shows exactly this code:";
    public const string PairWarning = "A paired phone can show its screen here and type on this laptop. You can remove it any time.";
    public const string Allow = "Allow";
    public const string Deny = "Deny";
    public const string StartFailed = "ScreenLink couldn't start its connection service.";
}
