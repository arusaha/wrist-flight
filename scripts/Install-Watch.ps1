<#
.SYNOPSIS
Installs Wrist Flight on a selected Wear OS watch and starts Shizuku.
.EXAMPLE
.\scripts\Install-Watch.ps1 -PairAddress '192.168.1.20:37123' -ConnectAddress '192.168.1.20:41867' -ShizukuApk "$env:USERPROFILE\Downloads\shizuku.apk"
.EXAMPLE
.\scripts\Install-Watch.ps1 -Serial '192.168.1.20:41867'
#>
[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$PairAddress,
    [string]$ConnectAddress,
    [string]$ShizukuApk,
    [string]$ApkPath,
    [switch]$RestartOnly
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
if (-not $ApkPath) { $ApkPath = Join-Path $repoPath 'dist\WristFlight-1.1.1.apk' }

if (-not $AdbPath) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $AdbPath = $adbCommand.Source }
    elseif ($env:ANDROID_HOME -and (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) {
        $AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    } else {
        $properties = Join-Path $repoPath 'local.properties'
        if (Test-Path -LiteralPath $properties) {
            $sdkLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
            if ($sdkLine) {
                $sdkPath = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
                $candidate = Join-Path $sdkPath 'platform-tools\adb.exe'
                if (Test-Path -LiteralPath $candidate) { $AdbPath = $candidate }
            }
        }
    }
}
if (-not $AdbPath) { throw 'ADB not found. Install Android Platform Tools, then pass -AdbPath C:\path\to\adb.exe.' }

if ($PairAddress) {
    # ADB prompts for the code. Do not save the pairing code in this script.
    & $AdbPath pair $PairAddress
    if ($LASTEXITCODE -ne 0) { throw 'Watch pairing failed.' }
}
if ($ConnectAddress) {
    & $AdbPath connect $ConnectAddress
    if ($LASTEXITCODE -ne 0) { throw 'Watch connection failed.' }
    if (-not $Serial) { $Serial = $ConnectAddress }
    # Some ADB versions return exit code 0 even when "connect" reports a failure.
    $connectionState = & $AdbPath -s $Serial get-state 2>&1
    if ($LASTEXITCODE -ne 0 -or "$connectionState".Trim() -ne 'device') {
        throw 'Watch did not remain connected. Keep it awake, enable Turn off automatic Wi-Fi in Developer options, and check the current connection port.'
    }
}
if (-not $Serial) {
    $devices = @(& $AdbPath devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Connect your watch and pass -Serial with its exact ADB device ID. Run adb devices to list devices.' }
    $Serial = $devices[0]
}
function Invoke-WatchAdb {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $result = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')`n$($result -join "`n")" }
    return ($result -join "`n").Trim()
}
$features = Invoke-WatchAdb -Arguments @('shell','pm','list','features')
if ($features -notmatch 'feature:android.hardware.type.watch') { throw 'The selected device is not a Wear OS watch. Select the watch, not your phone.' }
$model = Invoke-WatchAdb -Arguments @('shell','getprop','ro.product.model')
Write-Host "Selected watch: $model ($Serial)"

if (-not $RestartOnly) {
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw "APK not found: $ApkPath. Build the app or use -ApkPath." }
    if ($ShizukuApk) {
        $shizukuFile = (Resolve-Path -LiteralPath $ShizukuApk).Path
        Write-Host (Invoke-WatchAdb -Arguments @('install','-r',$shizukuFile))
    }
    Write-Host (Invoke-WatchAdb -Arguments @('install','-r',(Resolve-Path -LiteralPath $ApkPath).Path))
    # ADB installation normally allowlists restricted runtime permissions.
    # Foreground and background Sensors must be granted separately, in that order.
    try {
        $null = Invoke-WatchAdb -Arguments @('shell','pm','grant','dev.wristflight','android.permission.BODY_SENSORS')
        $api = [int](Invoke-WatchAdb -Arguments @('shell','getprop','ro.build.version.sdk'))
        if ($api -ge 33) {
            $null = Invoke-WatchAdb -Arguments @('shell','pm','grant','dev.wristflight','android.permission.BODY_SENSORS_BACKGROUND')
            $null = Invoke-WatchAdb -Arguments @('shell','pm','grant','dev.wristflight','android.permission.POST_NOTIFICATIONS')
        }
    } catch { Write-Warning "Automatic permission setup was not accepted: $_. Finish Sensors and notification permissions in the watch app's Setup screen." }
}

$shizukuPackage = Invoke-WatchAdb -Arguments @('shell','pm','path','moe.shizuku.privileged.api')
if ($shizukuPackage -notmatch '^package:') { throw 'Wrist Flight is installed. Download Shizuku from https://shizuku.rikka.app/download/ and rerun with -ShizukuApk pointing to that APK.' }
$null = Invoke-WatchAdb -Arguments @('shell','am','start','-n','moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity')
$packageInfo = Invoke-WatchAdb -Arguments @('shell','dumpsys','package','moe.shizuku.privileged.api')
$versionMatch = [regex]::Match($packageInfo, 'versionCode=(\d+)')
if ($versionMatch.Success -and [int]$versionMatch.Groups[1].Value -ge 1086) {
    # Shizuku 13.6+ replaced start.sh with the installed native starter.
    # Resolve its current install directory: this changes after package updates.
    $libraryMatch = [regex]::Match($packageInfo, '(?m)^\s*legacyNativeLibraryDir=(\S+)')
    if (-not $libraryMatch.Success) { throw 'Cannot locate the installed Shizuku native libraries.' }
    $libraryPath = $libraryMatch.Groups[1].Value
    if ($libraryPath -notmatch '^/data/app/[A-Za-z0-9_~=/+.-]+$') { throw 'Unexpected Shizuku native library path.' }
    $starterPaths = Invoke-WatchAdb -Arguments @('shell','find',$libraryPath,'-name','libshizuku.so','-type','f')
    $starter = ($starterPaths -split '\r?\n' | Where-Object { $_ -match '/libshizuku\.so$' } | Select-Object -First 1)
    if (-not $starter -or -not $starter.StartsWith("$libraryPath/", [StringComparison]::Ordinal) -or $starter -notmatch '^/data/app/[A-Za-z0-9_~=/+.-]+$') {
        throw 'Cannot locate Shizuku 13.6+ starter. Use the start command shown in the Shizuku app.'
    }
    Write-Host (Invoke-WatchAdb -Arguments @('shell',$starter))
} else {
    $starter = '/sdcard/Android/data/moe.shizuku.privileged.api/start.sh'
    $starterReady = $false
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        & $AdbPath -s $Serial shell test -f $starter 2>$null
        if ($LASTEXITCODE -eq 0) { $starterReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $starterReady) { throw 'Open Shizuku on the watch once, then rerun this script with -RestartOnly.' }
    Write-Host (Invoke-WatchAdb -Arguments @('shell','sh',$starter))
}
$serverPid = ''
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    $pidResult = & $AdbPath -s $Serial shell pidof shizuku_server 2>$null
    if ($LASTEXITCODE -eq 0 -and "$pidResult".Trim() -match '^\d+(\s+\d+)*$') {
        $serverPid = "$pidResult".Trim()
        break
    }
    Start-Sleep -Milliseconds 500
}
if (-not $serverPid) { throw 'The starter exited, but the Shizuku server is not running.' }
Write-Host "Shizuku server running (PID $serverPid)."
$null = Invoke-WatchAdb -Arguments @('shell','am','start','-n','dev.wristflight/.MainActivity')
Write-Host 'On the watch: Setup > Grant Shizuku access. Approve it, then tap Start automation.'
Write-Host 'Start with the watch on its charger. Verify removal/charging restores airplane mode before relying on it.'
Write-Host 'After reboot: reconnect ADB and rerun with -RestartOnly. No reinstall is needed.'
