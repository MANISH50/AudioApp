; ScreenLink for Windows installer (Inno Setup 6).
; Per-user install: no administrator rights, no drivers or services. The optional firewall rule (private networks only)
; is added later from inside the app, with one UAC prompt, only if the user asks for it.
; Built by CI:  ISCC.exe /DAppVersion=0.1.0 /DSourceDir=<dotnet publish folder> ScreenLink.iss

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\..\..\out\ScreenLink"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\..\out"
#endif

[Setup]
AppId={{108E38B2-AF20-4604-A3F6-54708EED86F6}
AppName=ScreenLink
AppVersion={#AppVersion}
AppVerName=ScreenLink {#AppVersion}
AppPublisher=ScreenLink
DefaultDirName={localappdata}\Programs\ScreenLink
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Windows 10 version 2004 or later (required by the Windows App SDK).
MinVersion=10.0.19041
OutputDir={#OutputDir}
OutputBaseFilename=ScreenLink-Setup-{#AppVersion}-x64
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupIconFile=..\assets\ScreenLink.ico
UninstallDisplayIcon={app}\ScreenLink.exe
UninstallDisplayName=ScreenLink
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\ScreenLink"; Filename: "{app}\ScreenLink.exe"
Name: "{autodesktop}\ScreenLink"; Filename: "{app}\ScreenLink.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\ScreenLink.exe"; Description: "{cm:LaunchProgram,ScreenLink}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Removes ScreenLink's firewall rules if the user added them (asks for administrator approval only in that case).
Filename: "{app}\ScreenLink.exe"; Parameters: "--firewall-remove"; Flags: runhidden waituntilterminated; RunOnceId: "ScreenLinkFirewallRemove"

[Code]
// Settings, pairings and this laptop's identity live in %LOCALAPPDATA%\ScreenLink. Keep them unless the user asks.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\ScreenLink');
    if DirExists(DataDir) and (not UninstallSilent) then
      if MsgBox('Also remove ScreenLink''s settings and paired phones?',
                mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(DataDir, True, True, True);
  end;
end;
