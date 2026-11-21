; Spatia for Windows installer (Inno Setup 6).
; Per-user install: no administrator rights, no system changes, no drivers or services. System-wide sound comes from
; Equalizer APO, which the user installs separately; connecting to it (one UAC prompt) happens later, inside the app.
; Built by CI:  ISCC.exe /DAppVersion=0.2.0 /DSourceDir=<dotnet publish folder> Spatia.iss

#ifndef AppVersion
  #define AppVersion "0.2.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\out\Spatia"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\out"
#endif

[Setup]
AppId={{9B6C1E2A-5F4D-4C8E-9A7B-3D2E1F0A6C55}
AppName=Spatia
AppVersion={#AppVersion}
AppVerName=Spatia {#AppVersion}
AppPublisher=Spatia
DefaultDirName={localappdata}\Programs\Spatia
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Windows 10 version 2004 or later (required by the Windows App SDK).
MinVersion=10.0.19041
OutputDir={#OutputDir}
OutputBaseFilename=Spatia-Setup-{#AppVersion}-x64
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\assets\Spatia.ico
UninstallDisplayIcon={app}\Spatia.exe
UninstallDisplayName=Spatia
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Spatia"; Filename: "{app}\Spatia.exe"
Name: "{autodesktop}\Spatia"; Filename: "{app}\Spatia.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Spatia.exe"; Description: "{cm:LaunchProgram,Spatia}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Stop shaping other apps' sound: removes Spatia's line from Equalizer APO's config.txt when that is possible without
; administrator rights, and otherwise switches Spatia's own settings file to "no processing" (exit code 3).
Filename: "{app}\Spatia.exe"; Parameters: "--apo-disconnect"; Flags: runhidden waituntilterminated; RunOnceId: "SpatiaApoDisconnect"

[Code]
// Settings live separately in %LOCALAPPDATA%\Spatia. Keep them unless the user asks to remove them.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  SettingsDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    SettingsDir := ExpandConstant('{localappdata}\Spatia');
    if DirExists(SettingsDir) and (not UninstallSilent) then
      if MsgBox('Also remove your Spatia settings (sound profiles and hearing profile)?',
                mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(SettingsDir, True, True, True);
  end;
end;
