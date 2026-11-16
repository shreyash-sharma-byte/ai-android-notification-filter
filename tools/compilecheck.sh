#!/usr/bin/env bash
# Compile-check the Java tree without touching build/ or signing anything.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
AJ="$SDK/platforms/android-35/android.jar"
OUT=/tmp/nc-compilecheck
rm -rf "$OUT"; mkdir -p "$OUT/gen/io/github/shreyashsharmabyte/notifycleaner"
cat > "$OUT/gen/io/github/shreyashsharmabyte/notifycleaner/BuildConfig.java" <<'CFG'
package io.github.shreyashsharmabyte.notifycleaner;
public final class BuildConfig {
    public static final String VERSION_NAME = "2.3.5";
    public static final int VERSION_CODE = 38;
    private BuildConfig() {}
}
CFG
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -nowarn -Xlint:-options -cp "$AJ" -d "$OUT/classes" @"$OUT/sources.txt"
echo "COMPILE OK"
