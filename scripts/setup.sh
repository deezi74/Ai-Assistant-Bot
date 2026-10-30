#!/usr/bin/env bash
# Prepares a PocketAgent checkout for building on Linux, macOS, Chromebook (Crostini) or WSL.
# Checks dependencies, fetches the pinned llama.cpp, and writes local.properties.
set -euo pipefail

LLAMA_TAG="${LLAMA_TAG:-b12017}"
NDK_VERSION="27.2.12479018"
CMAKE_VERSION="3.22.1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

say() { printf '\033[1;33m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null || die "git is required (apt install git)"
command -v java >/dev/null || die "JDK 17+ is required (apt install openjdk-17-jdk)"
JAVA_MAJOR=$(java -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print v[1]}')
[ "${JAVA_MAJOR:-0}" -ge 17 ] || die "JDK 17+ required, found $JAVA_MAJOR"

ARCH="$(uname -m)"
if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
  if [ "$(uname -s)" = "Linux" ]; then
    say "Note: Google ships the Android build tools (aapt2, NDK clang) for x86_64 Linux only."
    say "On ARM64 Linux / Termux / ARM Chromebooks, build in GitHub Actions instead (push to your repo)."
  fi
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK/cmdline-tools" ]; then
  say "Android SDK not found at $SDK, installing command-line tools"
  command -v unzip >/dev/null || die "unzip is required"
  mkdir -p "$SDK/cmdline-tools"
  TMP="$(mktemp -d)"
  curl -fsSL -o "$TMP/tools.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$TMP/tools.zip" -d "$TMP"
  mv "$TMP/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --licenses >/dev/null || true
say "Installing platform 35, build-tools 35.0.0, NDK $NDK_VERSION, CMake $CMAKE_VERSION"
"$SDKMANAGER" --install "platforms;android-35" "build-tools;35.0.0" "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION" >/dev/null

echo "sdk.dir=$SDK" > local.properties

if [ ! -f app/src/main/cpp/llama.cpp/CMakeLists.txt ]; then
  say "Cloning llama.cpp $LLAMA_TAG"
  git clone --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/llama.cpp
fi

chmod +x gradlew
say "Ready. Build with:  ./gradlew :app:assembleRelease"
say "APK lands in app/build/outputs/apk/release/"
