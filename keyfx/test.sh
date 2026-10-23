#!/usr/bin/env bash
# Runs keyfx's plain JVM tests: the classes with no Android imports (Fx, KeyLook) plus the
# tests, so no Android SDK is needed. With ANDROID_JAR set, also compiles every keyfx source
# against it as a quick build check.
# Needs JDK 9 or newer (for --release).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
MAIN=src/main/java/com/theo/keyfx
OUT=build/test
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/check"

javac --release 8 -Xlint:-options -d "$OUT/classes" "$MAIN/Fx.java" "$MAIN/KeyLook.java" \
    $(find src/test/java -name '*.java')
java -cp "$OUT/classes" com.theo.keyfx.KeyfxTests

if [ -n "${ANDROID_JAR:-}" ]; then
    javac --release 8 -Xlint:-options -nowarn -cp "$ANDROID_JAR" -d "$OUT/check" \
        $(find src/main/java -name '*.java')
    echo "keyfx compiles against android.jar"
fi
