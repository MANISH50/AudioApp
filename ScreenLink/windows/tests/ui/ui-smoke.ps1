<#
  ScreenLink for Windows - end-to-end UI smoke test (runs on a real Windows desktop in CI).
  Installs with the real installer, launches the installed app, requires a VISIBLE window, checks the main panel,
  flips the "Allow typing" switch (must be saved), checks that the app answers discovery on UDP 47421 and listens on
  TCP 47420, checks that a second copy refuses to start, screenshots, checks the log for errors, then uninstalls.
  Any failure throws, which fails the CI job.  Usage:  ui-smoke.ps1 -Installer <setup.exe> -OutDir <dir>
#>
param(
    [Parameter(Mandatory)] [string] $Installer,
    [Parameter(Mandatory)] [string] $OutDir
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class Win32 { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h); }
"@
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$env:SCREENLINK_NO_DIALOGS = '1'   # failures go to the log and exit code instead of blocking on a dialog
$env:SCREENLINK_DIAGNOSTICS = '1'
$A = [System.Windows.Automation.AutomationElement]
$T = [System.Windows.Automation.TreeScope]
$P = [System.Windows.Automation.PropertyCondition]
$dataDir = Join-Path $env:LOCALAPPDATA 'ScreenLink'
$logFile = Join-Path $dataDir 'logs\screenlink.log'

function Step($m) { Write-Host "=== $m" }
function Fail($m) {
    if (Test-Path $logFile) { Write-Host '--- screenlink.log ---'; Get-Content $logFile | Write-Host; Copy-Item $logFile (Join-Path $OutDir 'screenlink.log') -ErrorAction SilentlyContinue }
    throw $m
}

function Wait-Window([System.Diagnostics.Process] $proc, [string] $title, [int] $seconds = 60) {
    $cond = New-Object System.Windows.Automation.AndCondition(
        (New-Object $P($A::ProcessIdProperty, $proc.Id)),
        (New-Object $P($A::ControlTypeProperty, [System.Windows.Automation.ControlType]::Window)))
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { Fail "ScreenLink exited during startup (exit code $($proc.ExitCode))" }
        $w = $A::RootElement.FindFirst($T::Children, $cond)
        if ($w -and $w.Current.Name -eq $title -and [Win32]::IsWindowVisible([IntPtr]$w.Current.NativeWindowHandle)) { return $w }
        Start-Sleep -Milliseconds 500
    }
    Fail "No visible '$title' window after $seconds s"
}

function Find-ByName($root, [string] $name, [int] $seconds = 10) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $e = $root.FindFirst($T::Descendants, (New-Object $P($A::NameProperty, $name)))
        if ($e) { return $e }
        Start-Sleep -Milliseconds 300
    }
    return $null
}

function Shot($win, [string] $name) {
    [Win32]::SetForegroundWindow([IntPtr]$win.Current.NativeWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 700
    $r = $win.Current.BoundingRectangle
    $bmp = New-Object System.Drawing.Bitmap([int]$r.Width, [int]$r.Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen([int]$r.X, [int]$r.Y, 0, 0, $bmp.Size)
    $bmp.Save((Join-Path $OutDir "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

Step 'Install (silent, per-user)'
Remove-Item $dataDir -Recurse -Force -ErrorAction SilentlyContinue
$inst = Start-Process $Installer -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CURRENTUSER', "/LOG=$(Join-Path $OutDir 'install.log')" -Wait -PassThru
if ($inst.ExitCode -ne 0) { Fail "Installer exit code $($inst.ExitCode)" }
$exe = Join-Path $env:LOCALAPPDATA 'Programs\ScreenLink\ScreenLink.exe'
foreach ($f in 'ScreenLink.exe', 'ScreenLink.pri', 'ScreenLink.ico', 'unins000.exe') {
    if (-not (Test-Path (Join-Path (Split-Path $exe) $f))) { Fail "Installed app is missing $f" }
}
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\ScreenLink.lnk'
if (-not (Test-Path $startMenu)) { Fail 'Start-menu shortcut missing' }

Step 'Launch and wait for a visible window'
$proc = Start-Process $exe -PassThru
$win = Wait-Window $proc 'ScreenLink'
foreach ($text in 'Waiting for your phone.', 'Connect your phone', 'Paired phones', 'No phones paired yet.', 'Full screen (F11)') {
    if (-not (Find-ByName $win $text)) { Fail "Main window does not show '$text'" }
}
Shot $win 'main'

Step 'Identity was created and protected'
if (-not (Test-Path (Join-Path $dataDir 'identity.bin'))) { Fail 'identity.bin not created' }

Step 'Toggle "Allow typing from the phone" (saved to settings.json)'
$toggle = Find-ByName $win 'Allow typing from the phone'
if (-not $toggle) { Fail 'Typing switch not found' }
$tp = $toggle.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
$tp.Toggle()
Start-Sleep -Milliseconds 800
$settings = Get-Content (Join-Path $dataDir 'settings.json') -Raw | ConvertFrom-Json
if ($settings.AllowTyping -ne $false) { Fail 'AllowTyping=false was not saved' }
$tp.Toggle()
Start-Sleep -Milliseconds 800
$settings = Get-Content (Join-Path $dataDir 'settings.json') -Raw | ConvertFrom-Json
if ($settings.AllowTyping -ne $true) { Fail 'AllowTyping=true was not saved' }

Step 'Listening on TCP 47420'
$tcp = New-Object System.Net.Sockets.TcpClient
try { $tcp.Connect('127.0.0.1', 47420) } catch { Fail "TCP 47420 not accepting connections: $($_.Exception.Message)" }
$tcp.Dispose()

Step 'Answers discovery on UDP 47421'
$udp = New-Object System.Net.Sockets.UdpClient
$udp.Client.ReceiveTimeout = 3000
$q = [Text.Encoding]::ASCII.GetBytes('SCREENLINK?1')
$udp.Send($q, $q.Length, '127.0.0.1', 47421) | Out-Null
$ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
try { $reply = [Text.Encoding]::UTF8.GetString($udp.Receive([ref]$ep)) } catch { Fail 'No discovery reply' }
$udp.Dispose()
Write-Host "Discovery reply: $($reply -replace "`n", ' | ')"
if ($reply -notmatch '^SCREENLINK!1\n47420\n[0-9a-f]{64}\n.+') { Fail "Unexpected discovery reply: $reply" }

Step 'A second copy refuses to start'
$second = Start-Process $exe -PassThru
if (-not $second.WaitForExit(20000)) { Stop-Process -Id $second.Id -Force; Fail 'Second instance kept running' }
if ($proc.HasExited) { Fail 'The first instance exited' }

Step 'Close the app'
$win.GetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern).Close()
if (-not $proc.WaitForExit(15000)) { Stop-Process -Id $proc.Id -Force; Fail 'ScreenLink did not exit after closing its window' }

Step 'Check the log'
if (-not (Test-Path $logFile)) { Fail 'No log written' }
$log = Get-Content $logFile
$log | Write-Host
foreach ($must in 'main window shown', 'user interface ready', 'listening on port 47420', 'new identity created', 'already running') {
    if (-not ($log -match [regex]::Escape($must))) { Fail "Log is missing '$must'" }
}
$errors = $log | Where-Object { $_ -match ' ERROR ' }
if ($errors) { Fail "Errors in log:`n$($errors -join "`n")" }
Copy-Item $logFile (Join-Path $OutDir 'screenlink.log')

Step 'Uninstall (silent)'
Start-Process (Join-Path (Split-Path $exe) 'unins000.exe') -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru | Out-Null
Start-Sleep -Seconds 3
if (Test-Path $exe) { Fail 'ScreenLink.exe still present after uninstall' }
if (Test-Path $startMenu) { Fail 'Start-menu shortcut still present after uninstall' }

Write-Host 'UI smoke test passed.'
