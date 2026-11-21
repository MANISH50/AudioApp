using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Markup;
using Microsoft.UI.Xaml.XamlTypeInfo;
using Spatia.Core.Logic;

namespace Spatia.App;

/// <summary>Code-only WinUI application. Provides XAML type metadata for the Fluent control styles.</summary>
public sealed partial class App : Application, IXamlMetadataProvider, IDisposable
{
    private readonly XamlControlsXamlMetaDataProvider _metadata = new();
    private MainWindow? _window;

    public static StringTable S { get; private set; } = new([]);

    public App()
    {
        S = StringTable.Load(name => typeof(App).Assembly.GetManifestResourceStream($"Strings.{name}.Resources.resw"),
            System.Globalization.CultureInfo.CurrentUICulture);
        UnhandledException += (_, e) => { e.Handled = true; _window?.ShowError(e.Message); };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Resources.MergedDictionaries.Add(new XamlControlsResources());
        _window = new MainWindow();
        _window.Activate();
    }

    public void Dispose() => _window?.Dispose();

    public IXamlType GetXamlType(Type type) => _metadata.GetXamlType(type);
    public IXamlType GetXamlType(string fullName) => _metadata.GetXamlType(fullName);
    public XmlnsDefinition[] GetXmlnsDefinitions() => _metadata.GetXmlnsDefinitions();
}
