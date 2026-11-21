using System.Text.Json;
using System.Text.Json.Serialization;
using ScreenLink.Core.Logic;

namespace ScreenLink.Core.Settings;

public sealed class AppSettings
{
    /// <summary>Phones may type on this laptop. On by default; the switch is always visible on the main page.</summary>
    public bool AllowTyping { get; set; } = true;

    [JsonIgnore] public string? FilePath { get; private set; }

    public static AppSettings Load(string directory)
    {
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "settings.json");
        AppSettings s;
        try { s = File.Exists(path) ? JsonSerializer.Deserialize(File.ReadAllText(path), AppSettingsJson.Default.AppSettings) ?? new() : new(); }
        catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
        {
            AppLog.Error("settings", "unreadable, using defaults: " + e.GetType().Name);
            s = new();
        }
        s.FilePath = path;
        return s;
    }

    public void Save()
    {
        if (FilePath is null) return;
        try
        {
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(this, AppSettingsJson.Default.AppSettings));
            File.Move(tmp, FilePath, overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { AppLog.Error("settings", "save failed: " + e.GetType().Name); }
    }
}

[JsonSerializable(typeof(AppSettings))]
[JsonSourceGenerationOptions(WriteIndented = true)]
internal sealed partial class AppSettingsJson : JsonSerializerContext;
