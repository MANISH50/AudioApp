using System.Text.Json;
using System.Text.Json.Serialization;
using ScreenLink.Core.Logic;

namespace ScreenLink.Core.Security;

public sealed record PairedPhone(string Fingerprint, string Name, DateTimeOffset PairedAt);

[JsonSerializable(typeof(List<PairedPhone>))]
[JsonSourceGenerationOptions(WriteIndented = true)]
internal sealed partial class PairedPhonesJson : JsonSerializerContext;

/// <summary>Phones allowed to connect without asking (pinned certificate fingerprints). Thread-safe; saved atomically.</summary>
public sealed class PairedPhones
{
    private readonly string _path;
    private readonly Lock _gate = new();
    private List<PairedPhone> _list;

    public event Action? Changed;

    public PairedPhones(string directory)
    {
        Directory.CreateDirectory(directory);
        _path = Path.Combine(directory, "paired-phones.json");
        _list = Load(_path);
    }

    private static List<PairedPhone> Load(string path)
    {
        try
        {
            if (!File.Exists(path)) return [];
            var list = JsonSerializer.Deserialize(File.ReadAllText(path), PairedPhonesJson.Default.ListPairedPhone) ?? [];
            return [.. list.Where(p => p.Fingerprint is { Length: 64 } && p.Name is not null)];
        }
        catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException)
        {
            AppLog.Error("pairing", "paired phones list unreadable: " + e.GetType().Name);
            return [];
        }
    }

    public IReadOnlyList<PairedPhone> All { get { lock (_gate) return [.. _list]; } }

    public bool IsPaired(string fingerprint)
    {
        lock (_gate) return _list.Any(p => string.Equals(p.Fingerprint, fingerprint, StringComparison.Ordinal));
    }

    public void Add(string fingerprint, string name)
    {
        lock (_gate)
        {
            _list = [.. _list.Where(p => p.Fingerprint != fingerprint), new PairedPhone(fingerprint, name, DateTimeOffset.Now)];
            Save();
        }
        Changed?.Invoke();
    }

    public void Remove(string fingerprint)
    {
        lock (_gate)
        {
            _list = [.. _list.Where(p => p.Fingerprint != fingerprint)];
            Save();
        }
        Changed?.Invoke();
    }

    private void Save()
    {
        var tmp = _path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(_list, PairedPhonesJson.Default.ListPairedPhone));
        File.Move(tmp, _path, overwrite: true);
    }
}
