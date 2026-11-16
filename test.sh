#!/usr/bin/env bash
# Runs the pure-logic tests on a plain JDK. No Android SDK, no Gradle, no JUnit.
set -euo pipefail
cd "$(dirname "$0")"

PKG=io/github/shreyashsharmabyte/notifycleaner
SRC=src/$PKG
OUT=build/test
PURE="Decision Backoff CircuitBreaker RateLimiter Providers LogCodec Filters Prompt Urls Strictness AllowList LocalModels Telemetry LocalClassifier Categories Evidence Guards Senders"

# Guard: the testable core must stay free of Android types, or these tests cannot exist.
violations=0
for c in $PURE; do
  if grep -q "^import android" "$SRC/$c.java"; then
    echo "FAIL: $c.java imports android.* — it belongs to the pure core, not the shell"
    violations=1
  fi
done
[ "$violations" -eq 0 ] || exit 1

rm -rf "$OUT"
mkdir -p "$OUT"

FILES=""
for c in $PURE; do FILES="$FILES $SRC/$c.java"; done

javac --release 8 -nowarn -Xlint:-options -d "$OUT" $FILES test/LogicTest.java
java -cp "$OUT" io.github.shreyashsharmabyte.notifycleaner.LogicTest
