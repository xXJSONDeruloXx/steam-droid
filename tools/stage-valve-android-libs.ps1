param(
    [string]$SourceDir = "C:\Users\kurt\Downloads\steam_inspect\extracted\bins_androidarm64\androidarm64",
    [string]$TargetDir = ".\app\src\main\jniLibs\arm64-v8a"
)

$ErrorActionPreference = 'Stop'

if (!(Test-Path $SourceDir)) {
    Write-Error "SourceDir not found: $SourceDir"
}

New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

$files = @(
    'libsteamclient.so',
    'libsteamnetworkingsockets.so',
    'libtier0_s.so',
    'libvstdlib_s.so',
    'steamservice.so'
)

foreach ($f in $files) {
    $src = Join-Path $SourceDir $f
    $dst = Join-Path $TargetDir $f
    if (!(Test-Path $src)) {
        Write-Error "Missing required library: $src"
    }
    Copy-Item -Force $src $dst
    Write-Host "staged $f"
}

Write-Host "Done. Staged Valve Android libraries into $TargetDir"
