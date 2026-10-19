#!/usr/bin/env bash
# Builds the FUTO Tweaks APK without the Android SDK or Gradle.
#
# Toolchain (all from GitHub releases):
#   APKTOOL_JAR  apktool 2.12.1, for its bundled aapt2 and android-framework.jar
#   DX_JAR       dx-30.0.2.jar from dex2jar 2.4, converts .class to classes.dex
#   SIGNER_JAR   uber-apk-signer 1.3.0, zipalign and v1/v2/v3 signing
#
# Optional signing with your own key (otherwise uber-apk-signer's debug key is used):
#   KS_FILE, KS_ALIAS, KS_PASS, KS_KEY_PASS
#
# Java sources are compiled against hand-written stubs in nosdk/stubs, which are never
# packaged: on the phone the real framework and the LSPosed/Vector Xposed API provide them.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app/src/main"
OUT="$HERE/out"
: "${APKTOOL_JAR:?}" "${DX_JAR:?}" "${SIGNER_JAR:?}"

PACKAGE="com.theo.futotweaks"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"

rm -rf "$OUT" && mkdir -p "$OUT"/{tools,stubs-classes,classes,res-compiled,apk}

# aapt2 and the framework resource table, out of apktool
( cd "$OUT/tools" && unzip -q -o "$APKTOOL_JAR" prebuilt/linux/aapt2_64 prebuilt/android-framework.jar )
AAPT2="$OUT/tools/prebuilt/linux/aapt2_64"; chmod +x "$AAPT2"
FRAMEWORK="$OUT/tools/prebuilt/android-framework.jar"

# Compile stubs, then app sources against them (Java 8 bytecode, the newest dx reads)
javac --release 8 -nowarn -d "$OUT/stubs-classes" $(find "$HERE/stubs" -name '*.java')
javac --release 8 -Xlint:-options -cp "$OUT/stubs-classes" -d "$OUT/classes" $(find "$APP/java" -name '*.java')

# Only the app classes become classes.dex
java -cp "$DX_JAR" com.android.dx.command.Main --dex --min-sdk-version=26 \
    --output="$OUT/apk/classes.dex" "$OUT/classes"

# The source manifest has no package attribute (Gradle supplies it from namespace), so add it here
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

# Resources and manifest: compiled res is an input, not an -R overlay
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res-compiled/res.zip"
"$AAPT2" link -I "$FRAMEWORK" --manifest "$OUT/AndroidManifest.xml" -A "$APP/assets" \
    --min-sdk-version 30 --target-sdk-version 35 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o "$OUT/unsigned.apk" "$OUT/res-compiled/res.zip"

# Add the dex, then align and sign
( cd "$OUT/apk" && zip -q -X "$OUT/unsigned.apk" classes.dex )
SIGN_ARGS=()
if [ -n "${KS_FILE:-}" ]; then
    SIGN_ARGS=(--ks "$KS_FILE" --ksAlias "$KS_ALIAS" --ksPass "$KS_PASS" --ksKeyPass "$KS_KEY_PASS")
fi
java -jar "$SIGNER_JAR" -a "$OUT/unsigned.apk" -o "$OUT/signed" --allowResign "${SIGN_ARGS[@]}" >/dev/null
cp "$OUT"/signed/*.apk "$OUT/futo-tweaks-$VERSION_NAME.apk"
echo "Built $OUT/futo-tweaks-$VERSION_NAME.apk"
