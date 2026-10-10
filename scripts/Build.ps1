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
    $metadata = Get-Content 'app\build\outputs\apk\release\output-metadata.json' -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    if ($version -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid APK version metadata.' }
    $apkName = "WristFlight-$version.apk"
    $apk = Join-Path $outputPath $apkName
    Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination $apk
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $outputPath "$apkName.sha256"), "$hash  $apkName`n")
    Write-Host "Built and verified: $apk"
} finally { Pop-Location }
