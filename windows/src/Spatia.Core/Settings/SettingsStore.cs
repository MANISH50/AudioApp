using System.Text.Json;
using Spatia.Core.Models;

namespace Spatia.Core.Settings;

/// <summary>
/// Loads and saves <see cref="AppState"/> as JSON. Oversized, corrupt or hostile files fall back to defaults instead
/// of crashing; writes are atomic (temp file + replace).
/// </summary>
public sealed class SettingsStore(string directory, Func<SoundSettings, SoundSettings> validate)
{
    public const int MaxBytes = 512 * 1024;
    private readonly string _path = Path.Combine(directory, "settings.json");
    private readonly object _gate = new();

    public static string DefaultDirectory =>
        Path.Combine(System.Environment.GetFolderPath(System.Environment.SpecialFolder.LocalApplicationData), "Spatia");

    public AppState Load()
    {
        lock (_gate)
        {
            try
            {
                var info = new FileInfo(_path);
                if (!info.Exists || info.Length > MaxBytes) return new AppState();
                var state = JsonSerializer.Deserialize<AppState>(File.ReadAllText(_path), SpatiaJson.Options);
                return (state ?? new AppState()).Validated(validate);
            }
            catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException or NotSupportedException)
            {
                return new AppState();
            }
        }
    }

    public void Save(AppState state)
    {
        lock (_gate)
        {
            Directory.CreateDirectory(directory);
            var tmp = _path + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(state, SpatiaJson.Options));
            File.Move(tmp, _path, overwrite: true);
        }
    }
}
