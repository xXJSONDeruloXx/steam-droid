param(
    [string]$SdkDir = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$localProps = Join-Path $repoRoot 'local.properties'

if ([string]::IsNullOrWhiteSpace($SdkDir)) {
    throw "ANDROID_SDK_ROOT is not set and -SdkDir was not provided."
}

if (!(Test-Path $SdkDir)) {
    throw "SdkDir does not exist: $SdkDir"
}

$escaped = $SdkDir -replace '\\', '\\\\' -replace ':', '\:'
"sdk.dir=$escaped" | Set-Content -NoNewline $localProps
Write-Host "Wrote $localProps"
