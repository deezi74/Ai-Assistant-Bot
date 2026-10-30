# Prepares a PocketAgent checkout on Windows 10/11 (PowerShell 5+).
$ErrorActionPreference = "Stop"
$LlamaTag = if ($env:LLAMA_TAG) { $env:LLAMA_TAG } else { "b12017" }
$NdkVersion = "27.2.12479018"
$CmakeVersion = "3.22.1"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

function Require($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "$cmd not found. $hint" }
}
Require git "Install from https://git-scm.com/download/win"
Require java "Install JDK 17: winget install EclipseAdoptium.Temurin.17.JDK"

$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$SdkManager = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $SdkManager)) {
    Write-Host "==> Installing Android command-line tools into $Sdk"
    New-Item -ItemType Directory -Force -Path "$Sdk\cmdline-tools" | Out-Null
    $Zip = Join-Path $env:TEMP "cmdline-tools.zip"
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $Zip
    Expand-Archive -Force $Zip -DestinationPath "$env:TEMP\cmdtools"
    Move-Item -Force "$env:TEMP\cmdtools\cmdline-tools" "$Sdk\cmdline-tools\latest"
}
"y`ny`ny`ny`ny`ny`ny`n" | & $SdkManager --licenses | Out-Null
& $SdkManager --install "platforms;android-35" "build-tools;35.0.0" "ndk;$NdkVersion" "cmake;$CmakeVersion" | Out-Null

"sdk.dir=$($Sdk -replace '\\','\\')" | Set-Content -Encoding ASCII local.properties

if (-not (Test-Path "app\src\main\cpp\llama.cpp\CMakeLists.txt")) {
    Write-Host "==> Cloning llama.cpp $LlamaTag"
    git clone --depth 1 --branch $LlamaTag https://github.com/ggml-org/llama.cpp.git app\src\main\cpp\llama.cpp
}
Write-Host "==> Ready. Build with:  .\gradlew.bat :app:assembleRelease"
