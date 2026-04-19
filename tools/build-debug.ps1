param(
    [switch]$FetchValveLibs,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    throw "ANDROID_SDK_ROOT is not set."
}

if (!(Test-Path $env:ANDROID_SDK_ROOT)) {
    throw "ANDROID_SDK_ROOT does not exist: $($env:ANDROID_SDK_ROOT)"
}

if (![string]::IsNullOrWhiteSpace($JavaHome)) {
    if (!(Test-Path $JavaHome)) {
        throw "JavaHome does not exist: $JavaHome"
    }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\\bin;$env:PATH"
}

try {
    & java -version | Out-Null
} catch {
    throw "Java is not configured. Set JAVA_HOME or pass -JavaHome."
}

if (!(Test-Path '.\local.properties')) {
    & .\tools\write-local-properties.ps1
}

if ($FetchValveLibs) {
    & .\tools\fetch-valve-android-libs.ps1
}

if (!(Test-Path '.\gradlew.bat')) {
    throw "gradlew.bat not found."
}

cmd /c ".\gradlew.bat assembleDebug"
