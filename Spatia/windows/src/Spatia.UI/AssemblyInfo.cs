using System.Runtime.InteropServices;

// user32.dll (error dialog) is loaded from System32 only.
[assembly: DefaultDllImportSearchPaths(DllImportSearchPath.System32)]
