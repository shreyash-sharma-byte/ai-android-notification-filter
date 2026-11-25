#!/usr/bin/env bash
#
# Installs a minimal Android SDK under $HOME/android-sdk — no root, no Android Studio, ~500 MB.
# Only the pieces build.sh needs: platform android-35 and build-tools 35.0.0.
#
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
CMDLINE_VERSION="${CMDLINE_VERSION:-9862592}"
PLATFORM="${PLATFORM:-android-35}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
ZIP="commandlinetools-linux-${CMDLINE_VERSION}_latest.zip"
URL="https://dl.google.com/android/repository/$ZIP"

command -v java >/dev/null || { echo "ERROR: install a JDK (17 or newer) first." >&2; exit 1; }
command -v curl >/dev/null || { echo "ERROR: curl is required." >&2; exit 1; }
command -v unzip >/dev/null || { echo "ERROR: unzip is required." >&2; exit 1; }

if [ -d "$SDK/platforms/$PLATFORM" ] && [ -d "$SDK/build-tools/$BUILD_TOOLS_VERSION" ]; then
  echo "SDK already present at $SDK"
  exit 0
fi

mkdir -p "$SDK/cmdline-tools"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "== downloading command-line tools =="
curl -fL --retry 3 -o "$TMP/$ZIP" "$URL"
unzip -q "$TMP/$ZIP" -d "$TMP/unpacked"

rm -rf "$SDK/cmdline-tools/latest"
mv "$TMP/unpacked/cmdline-tools" "$SDK/cmdline-tools/latest"

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
PATH="$SDK/cmdline-tools/latest/bin:$PATH"

echo "== accepting licences =="
set +o pipefail
yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
set -o pipefail

echo "== installing platform and build tools =="
sdkmanager --sdk_root="$SDK" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS_VERSION"

echo
echo "Done. SDK at $SDK — now run ./build.sh"
