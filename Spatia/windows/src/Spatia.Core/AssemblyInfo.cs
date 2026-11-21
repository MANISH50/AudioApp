using System.Runtime.InteropServices;

// DLL hijacking protection (CWE-427): native libraries load only from the app's own folder or System32,
// never from the current directory or PATH.
[assembly: DefaultDllImportSearchPaths(DllImportSearchPath.AssemblyDirectory | DllImportSearchPath.System32)]
