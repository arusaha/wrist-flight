[CmdletBinding()]
param([string]$JavaHome, [string]$AndroidSdk)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if ($AndroidSdk) { $env:ANDROID_HOME = $AndroidSdk }
Push-Location $repoPath
try {
    & .\gradlew.bat --console=plain :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
    $outputPath = Join-Path $repoPath 'dist'
    New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
    $apk = Join-Path $outputPath 'WristFlight-1.0.apk'
    Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination $apk
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $outputPath 'WristFlight-1.0.apk.sha256'), "$hash  WristFlight-1.0.apk`n")
    Write-Host "Built and verified: $apk"
} finally { Pop-Location }
