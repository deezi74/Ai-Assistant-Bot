# One command from a fresh checkout to an installable TruAgent APK on Windows 10/11.
#   .\scripts\build-apk.ps1            # release APK
#   .\scripts\build-apk.ps1 -Debug     # debug APK
#   .\scripts\build-apk.ps1 -Install   # build, then adb install -r to a connected phone
param([switch]$Debug, [switch]$Install)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

& "$PSScriptRoot\setup.ps1"

$Variant = if ($Debug) { "debug" } else { "release" }
$Task = if ($Debug) { ":app:assembleDebug" } else { ":app:assembleRelease" }
Write-Host "==> Building $Task (first build downloads Gradle and compiles llama.cpp, 10-20 minutes)"
& .\gradlew.bat --no-daemon $Task
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

$Version = (Select-String -Path app\build.gradle.kts -Pattern 'versionName = "(.*)"').Matches[0].Groups[1].Value
$Apk = Get-ChildItem "app\build\outputs\apk\$Variant\*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
New-Item -ItemType Directory -Force -Path dist | Out-Null
$Out = "dist\TruAgent-$Version-$Variant-arm64.apk"
Copy-Item $Apk.FullName $Out -Force
(Get-FileHash $Out -Algorithm SHA256).Hash.ToLower() + "  " + (Split-Path $Out -Leaf) | Set-Content "$Out.sha256"
Write-Host "==> APK: $Out"

if ($Install) {
    $Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    $Adb = Join-Path $Sdk "platform-tools\adb.exe"
    if (-not (Test-Path $Adb)) { & (Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat") --install platform-tools | Out-Null }
    Write-Host "==> Installing on the connected phone (enable USB debugging first)"
    & $Adb install -r $Out
}
