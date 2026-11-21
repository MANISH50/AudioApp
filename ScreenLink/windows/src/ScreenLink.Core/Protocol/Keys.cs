namespace ScreenLink.Core.Protocol;

/// <summary>One step of keyboard input for the OS: a virtual key or a Unicode character, pressed or released.</summary>
public readonly record struct KeyStroke(ushort VirtualKey, char Unicode, bool Up, bool Extended)
{
    public bool IsUnicode => VirtualKey == 0;
}

/// <summary>
/// Turns phone keyboard messages into key strokes. Only an allow-list of keys is accepted (letters, digits, F1-F12,
/// navigation and editing keys), with Ctrl/Alt/Shift/Win as modifiers; anything else is ignored.
/// </summary>
public static class KeyMapping
{
    public const ushort Back = 0x08, Tab = 0x09, Return = 0x0D, Shift = 0x10, Control = 0x11, Menu = 0x12, Escape = 0x1B,
        Space = 0x20, PageUp = 0x21, PageDown = 0x22, End = 0x23, Home = 0x24, Left = 0x25, Up = 0x26, Right = 0x27,
        Down = 0x28, Insert = 0x2D, Delete = 0x2E, LWin = 0x5B, F1 = 0x70;

    public static bool IsAllowed(ushort vk) =>
        vk is Back or Tab or Return or Escape or Space or Insert or Delete
        || vk is >= PageUp and <= Down
        || vk is >= 0x30 and <= 0x39
        || vk is >= 0x41 and <= 0x5A
        || vk is >= F1 and < F1 + 12;

    private static bool IsExtended(ushort vk) => vk is >= PageUp and <= Down or Insert or Delete or LWin;

    /// <summary>Key strokes for a tap of <paramref name="vk"/> with <paramref name="modifiers"/> held; empty if not allowed.</summary>
    public static IReadOnlyList<KeyStroke> Press(ushort vk, byte modifiers)
    {
        if (!IsAllowed(vk) || modifiers > 15) return [];
        var mods = new List<ushort>(4);
        if ((modifiers & Wire.ModCtrl) != 0) mods.Add(Control);
        if ((modifiers & Wire.ModAlt) != 0) mods.Add(Menu);
        if ((modifiers & Wire.ModShift) != 0) mods.Add(Shift);
        if ((modifiers & Wire.ModWin) != 0) mods.Add(LWin);
        var list = new List<KeyStroke>(mods.Count * 2 + 2);
        foreach (var m in mods) list.Add(new KeyStroke(m, '\0', false, IsExtended(m)));
        list.Add(new KeyStroke(vk, '\0', false, IsExtended(vk)));
        list.Add(new KeyStroke(vk, '\0', true, IsExtended(vk)));
        for (var i = mods.Count - 1; i >= 0; i--) list.Add(new KeyStroke(mods[i], '\0', true, IsExtended(mods[i])));
        return list;
    }

    /// <summary>Key strokes that type <paramref name="text"/>: Unicode input per UTF-16 unit, Enter for line breaks, Tab for tabs.</summary>
    public static IReadOnlyList<KeyStroke> Text(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var list = new List<KeyStroke>(text.Length * 2);
        foreach (var c in text)
        {
            switch (c)
            {
                case '\r': break;
                case '\n': list.AddRange(Press(Return, 0)); break;
                case '\t': list.AddRange(Press(Tab, 0)); break;
                default:
                    if (char.IsControl(c)) break; // never inject other control characters
                    list.Add(new KeyStroke(0, c, false, false));
                    list.Add(new KeyStroke(0, c, true, false));
                    break;
            }
        }
        return list;
    }
}
