param(
    [string]$TargetDir = ".\app\src\main\jniLibs\arm64-v8a",
    [string]$TempDir = ".\.tmp\valve-android-libs"
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$findingsPath = Join-Path $repoRoot 'research\findings.json'
if (!(Test-Path $findingsPath)) {
    throw "findings.json not found: $findingsPath"
}

$findings = Get-Content $findingsPath -Raw | ConvertFrom-Json
$pkg = $findings.android_package
$cdnBase = $findings.cdn_base
$url = "$cdnBase$($pkg.file)"
$expectedSha = $pkg.sha2.ToLower()
$zipPath = Join-Path $TempDir 'bins_androidarm64_linuxarm64.zip'
$extractDir = Join-Path $TempDir 'extracted'

New-Item -ItemType Directory -Force -Path $TempDir | Out-Null
New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

Write-Host "Downloading Valve Android library package..."
Write-Host "  URL: $url"
Invoke-WebRequest -Uri $url -OutFile $zipPath

Write-Host "Verifying SHA-256..."
$actualSha = (Get-FileHash -Algorithm SHA256 $zipPath).Hash.ToLower()
if ($actualSha -ne $expectedSha) {
    throw "SHA-256 mismatch for $zipPath`nExpected: $expectedSha`nActual:   $actualSha"
}

if (Test-Path $extractDir) {
    Remove-Item -Recurse -Force $extractDir
}
Expand-Archive -LiteralPath $zipPath -DestinationPath $extractDir -Force

$sourceDir = Join-Path $extractDir 'androidarm64'
$files = @(
    'libsteamclient.so',
    'libsteamnetworkingsockets.so',
    'libtier0_s.so',
    'libvstdlib_s.so',
    'steamservice.so'
)

foreach ($f in $files) {
    $src = Join-Path $sourceDir $f
    $dst = Join-Path $TargetDir $f
    if (!(Test-Path $src)) {
        throw "Expected library not found in extracted package: $src"
    }
    Copy-Item -Force $src $dst
    Write-Host "staged $f"
}

Write-Host "Done. Valve Android libraries staged into $TargetDir"
