using System.Globalization;
using System.Xml;
using System.Xml.Linq;

namespace Spatia.Core.Logic;

/// <summary>
/// Reads standard .resw string files (Strings/&lt;culture&gt;/Resources.resw). Translators work with the usual .resw
/// format; the app loads it directly, with fallback to en-US, then to the key itself so a missing string is visible.
/// </summary>
public sealed class StringTable
{
    private readonly Dictionary<string, string> _strings;

    public StringTable(Dictionary<string, string> strings) => _strings = strings;

    public string this[string key] => _strings.TryGetValue(key, out var v) ? v : $"[{key}]";

    public string Format(string key, params object[] args) => string.Format(CultureInfo.CurrentCulture, this[key], args);

    public IReadOnlyCollection<string> Keys => _strings.Keys;

    /// <summary>Parses a .resw stream. DTDs are prohibited (XXE-safe); size is bounded.</summary>
    public static Dictionary<string, string> Parse(Stream resw)
    {
        var settings = new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null, MaxCharactersInDocument = 2_000_000 };
        using var reader = XmlReader.Create(resw, settings);
        var doc = XDocument.Load(reader);
        return doc.Root!.Elements("data")
            .Where(d => d.Attribute("name") is not null)
            .ToDictionary(d => d.Attribute("name")!.Value, d => d.Element("value")?.Value ?? "");
    }

    /// <summary>Loads the best culture available from <paramref name="open"/> (culture name → stream or null).</summary>
    public static StringTable Load(Func<string, Stream?> open, CultureInfo culture)
    {
        var merged = new Dictionary<string, string>();
        foreach (var name in new[] { "en-US", culture.TwoLetterISOLanguageName, culture.Name }.Distinct())
        {
            using var s = open(name);
            if (s is null) continue;
            foreach (var (k, v) in Parse(s)) merged[k] = v;
        }
        return new StringTable(merged);
    }
}
