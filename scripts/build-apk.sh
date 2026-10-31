#!/usr/bin/env bash
# One command from a fresh checkout to an installable TruAgent APK.
# Linux x86_64, macOS, WSL and x86_64 Chromebooks (Crostini). Installs the Android
# SDK pieces it needs (command-line tools, platform 35, build-tools, NDK, CMake),
# builds the release APK, copies it to dist/ and optionally installs it over adb.
#
#   scripts/build-apk.sh             # release APK
#   scripts/build-apk.sh --debug     # debug APK (installs next to the release one)
#   scripts/build-apk.sh --install   # build, then adb install -r to a connected phone
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
VARIANT=release
INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --debug) VARIANT=debug ;;
    --install) INSTALL=1 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1;33m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

case "$(uname -s)-$(uname -m)" in
  Linux-aarch64|Linux-arm64)
    if [ -n "${TERMUX_VERSION:-}" ] || [ -d /data/data/com.termux ]; then
      die "Termux/ARM64: Google's aapt2 and NDK compilers are x86_64-only, so the APK can't be built on the phone itself. Push to GitHub and let Actions build it, or run this on an x86_64 PC/Chromebook."
    fi
    die "ARM64 Linux: Google ships the Android build tools for x86_64 only. Build in GitHub Actions or on an x86_64 machine."
    ;;
esac

command -v curl >/dev/null || die "curl is required"
command -v unzip >/dev/null || die "unzip is required (apt install unzip)"
"$ROOT/scripts/setup.sh"

TASK=":app:assemble$(tr '[:lower:]' '[:upper:]' <<< "${VARIANT:0:1}")${VARIANT:1}"
say "Building $TASK (first build downloads Gradle and compiles llama.cpp, 10-20 minutes)"
./gradlew --no-daemon "$TASK"

VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -n1)
APK=$(ls -t app/build/outputs/apk/"$VARIANT"/*.apk | head -n1)
mkdir -p dist
OUT="dist/TruAgent-${VERSION}-${VARIANT}-arm64.apk"
cp "$APK" "$OUT"
( cd dist && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256" )
say "APK: $OUT ($(du -h "$OUT" | cut -f1))"

if [ "$INSTALL" = 1 ]; then
  ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
  [ -x "$ADB" ] || ADB=$(command -v adb || true)
  if [ -z "$ADB" ]; then
    "${ANDROID_HOME:-$HOME/Android/Sdk}/cmdline-tools/latest/bin/sdkmanager" --install platform-tools >/dev/null
    ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
  fi
  say "Installing on the connected phone (enable USB debugging first)"
  "$ADB" install -r "$OUT"
fi
