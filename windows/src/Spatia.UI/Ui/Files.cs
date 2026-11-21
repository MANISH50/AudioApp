using Microsoft.UI.Xaml;

namespace Spatia.UI.Ui;

/// <summary>File picking for an unpackaged app (the picker must be bound to the window handle).</summary>
public static class Files
{
    public static async Task<string?> PickAsync(Window window, IEnumerable<string> extensions)
    {
        var picker = new Windows.Storage.Pickers.FileOpenPicker();
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(window));
        foreach (var e in extensions) picker.FileTypeFilter.Add(e);
        var file = await picker.PickSingleFileAsync();
        return file?.Path;
    }

    /// <summary>Reads a user-picked file only if it is at most <paramref name="maxBytes"/> (input validation before parsing).</summary>
    public static byte[]? ReadBounded(string path, long maxBytes)
    {
        var info = new FileInfo(path);
        if (!info.Exists || info.Length > maxBytes) return null;
        return File.ReadAllBytes(path);
    }
}
