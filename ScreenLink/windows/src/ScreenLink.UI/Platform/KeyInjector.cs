using System.Runtime.InteropServices;
using ScreenLink.Core.Logic;
using ScreenLink.Core.Net;
using ScreenLink.Core.Protocol;

namespace ScreenLink.UI.Platform;

/// <summary>
/// Types into whatever window has focus, with Windows' SendInput. Each tap sends its modifiers down and up in the same
/// batch, so no key is ever left held. Windows blocks input into windows of programs running as administrator (UIPI)
/// unless ScreenLink itself is elevated; that is reported once in the log.
/// </summary>
public sealed partial class KeyInjector : IKeySink
{
    private const uint InputKeyboard = 1;
    private const uint KeyEventExtended = 0x1, KeyEventUp = 0x2, KeyEventUnicode = 0x4;
    private const uint MapVkToVsc = 0;
    private bool _warned;

    [StructLayout(LayoutKind.Sequential)]
    private struct MouseInput { public int Dx, Dy; public uint MouseData, Flags, Time; public nint ExtraInfo; }

    [StructLayout(LayoutKind.Sequential)]
    private struct KeybdInput { public ushort Vk, Scan; public uint Flags, Time; public nint ExtraInfo; }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion { [FieldOffset(0)] public MouseInput Mi; [FieldOffset(0)] public KeybdInput Ki; }

    [StructLayout(LayoutKind.Sequential)]
    private struct Input { public uint Type; public InputUnion U; }

    [LibraryImport("user32.dll", SetLastError = true)]
    private static partial uint SendInput(uint count, [In] Input[] inputs, int size);

    [LibraryImport("user32.dll", EntryPoint = "MapVirtualKeyW")]
    private static partial uint MapVirtualKey(uint code, uint mapType);

    public void Send(IReadOnlyList<KeyStroke> strokes)
    {
        ArgumentNullException.ThrowIfNull(strokes);
        if (strokes.Count == 0) return;
        var inputs = new Input[strokes.Count];
        for (var i = 0; i < strokes.Count; i++)
        {
            var s = strokes[i];
            var ki = new KeybdInput();
            if (s.IsUnicode)
            {
                ki.Scan = s.Unicode;
                ki.Flags = KeyEventUnicode;
            }
            else
            {
                ki.Vk = s.VirtualKey;
                ki.Scan = (ushort)MapVirtualKey(s.VirtualKey, MapVkToVsc);
                if (s.Extended) ki.Flags |= KeyEventExtended;
            }
            if (s.Up) ki.Flags |= KeyEventUp;
            inputs[i] = new Input { Type = InputKeyboard, U = new InputUnion { Ki = ki } };
        }
        var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>());
        if (sent != inputs.Length && !_warned)
        {
            _warned = true;
            AppLog.Info("keyboard", $"Windows accepted {sent} of {inputs.Length} key events (the focused app may run as administrator)");
        }
    }
}
