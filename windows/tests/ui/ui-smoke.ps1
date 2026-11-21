<#
  Spatia for Windows - end-to-end UI smoke test (runs on a real Windows desktop in CI).
  Installs with the real installer, connects Spatia to a stand-in Equalizer APO config folder (SPATIA_APO_DIR, a temp
  folder with a config.txt), launches the installed app, requires a VISIBLE window, visits every page, changes mode,
  environment and intensity and checks that spatia.txt and the rendered impulse response change, plays the test sound,
  screenshots each page, checks the log for errors, then uninstalls (which must disconnect again).
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
$env:SPATIA_DIAGNOSTICS = '1'  # also log first-chance exceptions, to pinpoint native crashes
$A = [System.Windows.Automation.AutomationElement]
$T = [System.Windows.Automation.TreeScope]
$P = [System.Windows.Automation.PropertyCondition]
$logFile = Join-Path $env:LOCALAPPDATA 'Spatia\logs\spatia.log'

function Step($m) { Write-Host "=== $m" }
$script:started = Get-Date
function Fail($m) {
    if (Test-Path $logFile) { Write-Host '--- spatia.log ---'; Get-Content $logFile | Write-Host; Copy-Item $logFile (Join-Path $OutDir 'spatia.log') -ErrorAction SilentlyContinue }
    Write-Host '--- Windows Application event log (Spatia) ---'
    Get-WinEvent -FilterHashtable @{ LogName = 'Application'; StartTime = $script:started } -ErrorAction SilentlyContinue |
        Where-Object { $_.Message -match 'Spatia' } | Select-Object -First 8 |
        ForEach-Object { Write-Host "[$($_.TimeCreated) id=$($_.Id) $($_.ProviderName)]"; Write-Host $_.Message }
    throw $m
}

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
foreach ($f in 'Spatia.exe', 'Spatia.pri', 'spatia_dsp.dll', 'Spatia.ico', 'unins000.exe') {
    if (-not (Test-Path (Join-Path (Split-Path $exe) $f))) { Fail "Installed app is missing $f" }
}
$startMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Spatia.lnk'
if (-not (Test-Path $startMenu)) { Fail 'Start-menu shortcut missing' }

# Full crash dumps for Spatia.exe so a native UI failure can be analysed (CI runner only).
$dumpDir = Join-Path $OutDir 'dumps'
New-Item -ItemType Directory -Force -Path $dumpDir | Out-Null
$wer = 'HKLM:\SOFTWARE\Microsoft\Windows\Windows Error Reporting\LocalDumps\Spatia.exe'
New-Item -Path $wer -Force | Out-Null
Set-ItemProperty -Path $wer -Name DumpFolder -Value $dumpDir -Type ExpandString
Set-ItemProperty -Path $wer -Name DumpType -Value 2 -Type DWord
Set-ItemProperty -Path $wer -Name DumpCount -Value 3 -Type DWord

function Analyze-Dump {
    $dump = Get-ChildItem $dumpDir -Filter *.dmp -ErrorAction SilentlyContinue | Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $dump) { Write-Host 'No crash dump was written.'; return }
    $cdb = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\Debuggers\x64\cdb.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $cdb) { Write-Host "Dump at $($dump.FullName) (cdb.exe not available to analyse it)"; return }
    Write-Host "--- !analyze of $($dump.Name) (stowed exception details) ---"
    $out = & $cdb.FullName -z $dump.FullName -c '.symfix; !analyze -v; q' 2>&1
    $out | Select-String -Pattern 'STOWED|originate|Error|HRESULT|EXCEPTION_CODE|ERROR_CODE|Message|BUCKET|FAILURE|Xaml|0x80|0xc0' | Select-Object -First 80 | ForEach-Object { Write-Host $_.Line }
    $out | Out-File (Join-Path $OutDir 'analyze.txt')
}

# ---------------------------------------------------------------- stand-in Equalizer APO + connect helper
Step 'Connect to a stand-in Equalizer APO config folder (--apo-connect, not elevated)'
$apoDir = Join-Path $env:TEMP 'spatia-apo-test'
Remove-Item $apoDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $apoDir | Out-Null
$apoConfig = Join-Path $apoDir 'config.txt'
Set-Content -Path $apoConfig -Value "Preamp: 0 dB" -Encoding ascii
$env:SPATIA_APO_DIR = $apoDir
$spatiaTxt = Join-Path $apoDir 'Spatia\spatia.txt'
$include = 'Include: Spatia\spatia.txt'
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
foreach ($i in 1, 2) {
    $c = Start-Process $exe -ArgumentList '--apo-connect', $sid -Wait -PassThru
    if ($c.ExitCode -ne 0) { Fail "--apo-connect exit code $($c.ExitCode)" }
}
$lines = @(Get-Content $apoConfig | Where-Object { $_.Trim() -eq $include })
if ($lines.Count -ne 1) { Fail "config.txt should contain the include line exactly once, found $($lines.Count)" }
if (-not (Test-Path (Join-Path $apoDir 'config.txt.spatia-backup'))) { Fail 'config.txt backup missing' }
if (-not (Test-Path $spatiaTxt)) { Fail 'Spatia\spatia.txt not created by connect' }

function Wait-Config([scriptblock] $ok, [string] $what, [int] $seconds = 10) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $t = if (Test-Path $spatiaTxt) { Get-Content $spatiaTxt -Raw } else { '' }
        if (& $ok $t) { return $t }
        Start-Sleep -Milliseconds 300
    }
    Write-Host '--- spatia.txt ---'; Write-Host $t
    Fail "spatia.txt: $what"
}

# ---------------------------------------------------------------- launch
Write-Host 'Resource index files installed:'
Get-ChildItem (Split-Path $exe) -Filter *.pri | ForEach-Object { Write-Host "  $($_.Name)  $($_.Length) bytes" }
Step 'Launch installed app and wait for a visible window'
$proc = Start-Process $exe -PassThru
Start-Sleep -Seconds 6
if ($proc.HasExited) {
    # Crash during startup: find which page triggers it by starting on each page in turn (diagnostics mode).
    Write-Host "Spatia exited with code $($proc.ExitCode). Bisecting by start page:"
    if (Test-Path $logFile) { Get-Content $logFile | Write-Host }
    foreach ($tag in 'status', 'other', 'output', 'sound', 'home') {
        Remove-Item $logFile -ErrorAction SilentlyContinue
        $env:SPATIA_START_PAGE = $tag
        $t = Start-Process $exe -PassThru
        Start-Sleep -Seconds 6
        if ($t.HasExited) {
            Write-Host "  start page '$tag': CRASHED (exit $($t.ExitCode)); last log lines:"
            if (Test-Path $logFile) { Get-Content $logFile -Tail 6 | ForEach-Object { Write-Host "      $_" } }
        } else { Write-Host "  start page '$tag': OK"; Stop-Process -Id $t.Id -Force }
    }
    Remove-Item Env:\SPATIA_START_PAGE
    foreach ($switch in 'SPATIA_DIAG_NO_MICA', 'SPATIA_DIAG_NO_ICON', 'SPATIA_DIAG_MINIMAL') {
        Remove-Item $logFile -ErrorAction SilentlyContinue
        Set-Item "Env:\$switch" '1'
        $t = Start-Process $exe -PassThru
        Start-Sleep -Seconds 6
        if ($t.HasExited) { Write-Host "  $switch : CRASHED (exit $($t.ExitCode))" } else { Write-Host "  $switch : OK (no crash)"; Stop-Process -Id $t.Id -Force }
        if (Test-Path $logFile) { Get-Content $logFile -Tail 3 | ForEach-Object { Write-Host "      $_" } }
        Remove-Item "Env:\$switch"
    }
    Analyze-Dump
    Fail "Spatia crashed during startup (exit code $($proc.ExitCode))"
}
$win = Wait-Window $proc 'Spatia'
Write-Host "Window visible: handle $($win.Current.NativeWindowHandle), $($win.Current.BoundingRectangle)"

$pages = [ordered]@{
    'nav-home'   = 'System-wide sound (Equalizer APO)'
    'nav-sound'  = 'Environment'
    'nav-output' = 'Active output'
    'nav-other'  = 'How Spatia reaches every app'
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
if (Find-ByName $win 'Open file' 1) { Fail 'The file player should be gone' }

Step 'Home: connected state, settings written to spatia.txt'
Select-Item (Find-ById $win 'nav-home')
if (-not (Find-ById $win 'apo-disconnect')) { Fail 'Home does not show the connected state (Disconnect button)' }
$before = Wait-Config { param($t) $t -match 'Preamp:' } 'no Preamp line after startup'

Step 'Home: change mode'
$mode = Find-ByName $win 'Movie'
if (-not $mode) { Fail "'Movie' mode not found" }
Select-Item $mode
$afterMode = Wait-Config { param($t) $t -ne $before -and $t -match 'Filter: ON' } 'did not change after choosing Movie'

Step 'Home: Spatia off and on again'
$power = Find-ById $win 'power'
if (-not $power) { Fail 'On/off switch not found' }
$tp = $power.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern)
$tp.Toggle()
Wait-Config { param($t) $t -match 'Spatia is off' -and $t -notmatch 'Filter:' } 'not neutral when Spatia is off' | Out-Null
$tp.Toggle()
Wait-Config { param($t) $t -match 'Filter: ON' } 'filters not back after turning Spatia on' | Out-Null

Step 'Home: test sound'
$test = Find-ById $win 'test-sound'
if (-not $test) { Fail "'Test sound' button not found" }
$test.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
Start-Sleep -Seconds 2
if ($proc.HasExited) { Fail 'Spatia exited after Test sound' }
Shot $win 'home-test-sound'

Step 'Home: disconnect and connect again (in-process: the stand-in folder is writable)'
(Find-ById $win 'apo-disconnect').GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
$connect = $null
$deadline = (Get-Date).AddSeconds(10)
while (-not $connect -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300; $connect = Find-ById $win 'apo-connect' }
if (-not $connect) { Fail 'Connect button did not appear after Disconnect' }
if (@(Get-Content $apoConfig | Where-Object { $_.Trim() -eq $include }).Count -ne 0) { Fail 'Disconnect left the include line' }
Shot $win 'home-disconnected'
$connect.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
$disc = $null
$deadline = (Get-Date).AddSeconds(10)
while (-not $disc -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300; $disc = Find-ById $win 'apo-disconnect' }
if (-not $disc) { Fail 'Did not reconnect' }
if (@(Get-Content $apoConfig | Where-Object { $_.Trim() -eq $include }).Count -ne 1) { Fail 'Reconnect did not restore exactly one include line' }
Shot $win 'home-connected'

Step 'Sound: choose Hall and raise intensity'
Select-Item (Find-ById $win 'nav-sound')
$hall = Find-ByName $win 'Hall'
if (-not $hall) { Fail "'Hall' environment not found" }
Select-Item $hall
$withHall = Wait-Config { param($t) $t -match 'Convolution: env_[0-9a-f]+\.wav' } 'no Convolution line after choosing Hall'
$ir1 = @(Get-ChildItem (Join-Path $apoDir 'Spatia') -Filter 'env_*.wav')
if ($ir1.Count -ne 1) { Fail "Expected one impulse response, found $($ir1.Count)" }
if ($ir1[0].Length -lt 48000 * 2 * 4 * 2) { Fail "Impulse response too short ($($ir1[0].Length) bytes)" }
$slider = Find-ByName $win 'Effect intensity'
if (-not $slider) { Fail "'Effect intensity' slider not found" }
$rv = $slider.GetCurrentPattern([System.Windows.Automation.RangeValuePattern]::Pattern)
$rv.SetValue(0.8)
Wait-Config { param($t) $t -ne $withHall -and $t -match 'Convolution: env_' } 'did not change after moving intensity' | Out-Null
$ir2 = @(Get-ChildItem (Join-Path $apoDir 'Spatia') -Filter 'env_*.wav')
if ($ir2.Count -ne 1 -or $ir2[0].Name -eq $ir1[0].Name) { Fail 'Impulse response was not re-rendered (or stale files were left)' }
if ($proc.HasExited) { Fail 'Spatia exited after changing the environment' }
if (-not (Find-ByName $win 'An empty concert hall: a short pause, then a long, warm echo from every wall.')) { Fail 'Hall description not shown' }
Shot $win 'sound-hall'
Copy-Item $spatiaTxt (Join-Path $OutDir 'spatia.txt')

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
# First-chance exceptions are expected occasionally (handled ones); they are printed above for review.
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
if (@(Get-Content $apoConfig | Where-Object { $_.Trim() -eq $include }).Count -ne 0) { Fail 'Uninstall did not disconnect from Equalizer APO' }
Remove-Item Env:\SPATIA_APO_DIR
Remove-Item $apoDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host 'UI smoke test passed.'
