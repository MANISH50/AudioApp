<#
  Spatia for Windows - end-to-end UI smoke test (runs on a real Windows desktop in CI).
  Installs with the real installer, launches the installed app, requires a VISIBLE window, visits every page,
  plays the sample, changes an environment, screenshots each page, checks the log for errors, then uninstalls.
  Any failure throws, which fails the CI job.  Usage:  ui-smoke.ps1 -Installer <setup.exe> -OutDir <dir>
#>
param(
    [Parameter(Mandatory)] [string] $Installer,
    [Parameter(Mandatory)] [string] $OutDir
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing, System.Windows.Forms
Add-Type @"
using System; using System.Runtime.InteropServices;
public static class Win32 { [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h); }
"@
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$env:SPATIA_NO_DIALOGS = '1'   # failures go to the log and exit code instead of blocking on a dialog
$A = [System.Windows.Automation.AutomationElement]
$T = [System.Windows.Automation.TreeScope]
$P = [System.Windows.Automation.PropertyCondition]
$logFile = Join-Path $env:LOCALAPPDATA 'Spatia\logs\spatia.log'

function Step($m) { Write-Host "=== $m" }
function Fail($m) { if (Test-Path $logFile) { Write-Host '--- spatia.log ---'; Get-Content $logFile | Write-Host }; throw $m }

function Wait-Window([System.Diagnostics.Process] $proc, [string] $title, [int] $seconds = 60) {
    $cond = New-Object System.Windows.Automation.AndCondition(
        (New-Object $P($A::ProcessIdProperty, $proc.Id)),
        (New-Object $P($A::ControlTypeProperty, [System.Windows.Automation.ControlType]::Window)))
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { Fail "Spatia exited during startup (exit code $($proc.ExitCode))" }
        $w = $A::RootElement.FindFirst($T::Children, $cond)
        if ($w -and $w.Current.Name -eq $title -and [Win32]::IsWindowVisible([IntPtr]$w.Current.NativeWindowHandle)) { return $w }
        Start-Sleep -Milliseconds 500
    }
    Fail "No visible '$title' window after $seconds s (process still running: $(-not $proc.HasExited))"
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

function Find-ById($root, [string] $id) { $root.FindFirst($T::Descendants, (New-Object $P($A::AutomationIdProperty, $id))) }

function Select-Item($e) {
    $sp = $null
    if ($e.TryGetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern, [ref]$sp)) { $sp.Select(); return }
    $ip = $null
    if ($e.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$ip)) { $ip.Invoke(); return }
    Fail "Element '$($e.Current.Name)' can't be selected or invoked"
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

# ---------------------------------------------------------------- install
Step 'Install (silent, per-user)'
if (Test-Path $logFile) { Remove-Item $logFile -Force }
$inst = Start-Process $Installer -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/CURRENTUSER', "/LOG=$(Join-Path $OutDir 'install.log')" -Wait -PassThru
if ($inst.ExitCode -ne 0) { Fail "Installer exit code $($inst.ExitCode)" }
$exe = Join-Path $env:LOCALAPPDATA 'Programs\Spatia\Spatia.exe'
foreach ($f in 'Spatia.exe', 'spatia_dsp.dll', 'Spatia.ico', 'unins000.exe') {
    if (-not (Test-Path (Join-Path (Split-Path $exe) $f))) { Fail "Installed app is missing $f" }
}
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Spatia.lnk'
if (-not (Test-Path $startMenu)) { Fail 'Start-menu shortcut missing' }

# ---------------------------------------------------------------- launch
Step 'Launch installed app and wait for a visible window'
$proc = Start-Process $exe -PassThru
$win = Wait-Window $proc 'Spatia'
Write-Host "Window visible: handle $($win.Current.NativeWindowHandle), $($win.Current.BoundingRectangle)"

$pages = [ordered]@{
    'nav-player' = 'Now playing'
    'nav-sound'  = 'Environment'
    'nav-output' = 'Active output'
    'nav-other'  = "Why other apps aren't enhanced yet"
    'nav-status' = 'What this PC supports'
}
foreach ($id in $pages.Keys) {
    Step "Open page $id"
    $item = Find-ById $win $id
    if (-not $item) { Fail "Navigation item $id not found" }
    Select-Item $item
    if (-not (Find-ByName $win $pages[$id])) { Fail "Page $id did not show '$($pages[$id])'" }
    if ($proc.HasExited) { Fail "Spatia exited after opening $id" }
    Shot $win $id
}

Step 'Player: play the built-in sample'
Select-Item (Find-ById $win 'nav-player')
$play = Find-ByName $win 'Play sample'
if (-not $play) { Fail "'Play sample' button not found" }
$play.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
Start-Sleep -Seconds 3
if ($proc.HasExited) { Fail 'Spatia exited after Play sample' }
$playing = Find-ByName $win 'Spatia sample track' 5
$noDevice = $win.FindFirst($T::Descendants, (New-Object $P($A::NameProperty, "The playback device stopped or was disconnected. Pick another output in Windows sound settings, then press Play.")))
if (-not $playing -and -not $noDevice) { Fail 'Neither the sample title nor a clear device message appeared' }
Write-Host ("Sample: " + $(if ($playing) { 'playing' } else { 'no audio device on this machine (message shown)' }))
Shot $win 'player-sample'

Step 'Sound: choose Hall and raise intensity'
Select-Item (Find-ById $win 'nav-sound')
$hall = Find-ByName $win 'Hall'
if (-not $hall) { Fail "'Hall' environment not found" }
Select-Item $hall
$slider = Find-ByName $win 'Effect intensity'
if ($slider) {
    $rv = $null
    if ($slider.TryGetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern, [ref]$rv)) { $rv.SetValue(0.8) }
}
Start-Sleep -Seconds 2
if ($proc.HasExited) { Fail 'Spatia exited after changing the environment' }
if (-not (Find-ByName $win 'An empty concert hall: a short pause, then a long, warm echo from every wall.')) { Fail 'Hall description not shown' }
Shot $win 'sound-hall'

# ---------------------------------------------------------------- close + log
Step 'Close the app'
$wp = $win.GetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern)
$wp.Close()
if (-not $proc.WaitForExit(15000)) { Stop-Process -Id $proc.Id -Force; Fail 'Spatia did not exit after closing its window' }

Step 'Check the log'
if (-not (Test-Path $logFile)) { Fail 'No log written' }
$log = Get-Content $logFile
$log | Write-Host
foreach ($must in 'main window shown', 'user interface ready') {
    if (-not ($log -match [regex]::Escape($must))) { Fail "Log is missing '$must'" }
}
$errors = $log | Where-Object { $_ -match ' ERROR ' }
if ($errors) { Fail "Errors in log:`n$($errors -join "`n")" }
Copy-Item $logFile (Join-Path $OutDir 'spatia.log')

# ---------------------------------------------------------------- engine missing must be visible, not silent
Step 'Engine missing: the app must show a clear message, not fail silently'
$broken = Join-Path $env:TEMP 'spatia-broken'
Remove-Item $broken -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item (Split-Path $exe) $broken -Recurse
Remove-Item (Join-Path $broken 'spatia_dsp.dll')
$p2 = Start-Process (Join-Path $broken 'Spatia.exe') -PassThru
$w2 = Wait-Window $p2 'Spatia'
if (-not (Find-ByName $w2 "Spatia's audio engine is missing")) { Fail 'Missing-engine message not shown' }
Shot $w2 'engine-missing'
$w2.GetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern).Close()
$p2.WaitForExit(15000) | Out-Null

# ---------------------------------------------------------------- uninstall
Step 'Uninstall (silent)'
$un = Start-Process (Join-Path (Split-Path $exe) 'unins000.exe') -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait -PassThru
Start-Sleep -Seconds 3
if (Test-Path $exe) { Fail 'Spatia.exe still present after uninstall' }
if (Test-Path $startMenu) { Fail 'Start-menu shortcut still present after uninstall' }

Write-Host 'UI smoke test passed.'
