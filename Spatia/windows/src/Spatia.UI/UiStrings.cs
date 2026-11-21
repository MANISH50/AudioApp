using System.Globalization;
using Spatia.Core.Logic;

namespace Spatia.UI;

/// <summary>User-visible text, loaded from the embedded .resw files (see Strings/en-US/Resources.resw).</summary>
public static class UiStrings
{
    public static StringTable S { get; private set; } = new([]);

    public static void Load(CultureInfo culture) =>
        S = StringTable.Load(name => typeof(UiStrings).Assembly.GetManifestResourceStream($"Strings.{name}.Resources.resw"), culture);
}
