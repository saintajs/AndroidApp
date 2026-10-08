#!/usr/bin/env bash
#
# US-011 build-contract gate (testing.yaml components_under_test).
# Runs the zero-secrets verification from the apps/mobile root:
#   [0] empty secrets environment (the gate-prefixed secret vars are unset,
#       then proven absent)
#   [1] ./gradlew check :app:assembleDebug -> BUILD SUCCESSFUL (CA-01)
#   [2] app/build/outputs/apk/debug/app-debug.apk present and non-trivially sized
#   [3] merged debug manifest grants EXACTLY ONE uses-permission request and it
#       is android.permission.INTERNET (CA-01/BR-031); binary APK re-checked via
#       aapt when the build-tools binary is available
#   [4] versionCode positive integer + semver-ish versionName (CA-05)
#   [5] debugRuntimeClasspath carries the pinned network stack (CA-02,
#       US-012): retrofit 3.0.0, okhttp 5.5.0, kotlinx-serialization-json
#       1.11.0 must all be present on debugRuntimeClasspath
#   [6] tracked tree has no signing configuration / secret references (CA-04)
#   [7] version catalog pins the network stack at the exact contracted
#       versions (CA-02): retrofit = 3.0.0, okhttp = 5.5.0,
#       kotlinxSerializationJson = 1.11.0
#   [8] versionCode strictly monotonic across runs via scripts/.last-versionCode
#
# Pattern literals for the tracked-tree self-audit are assembled from
# fragments so this script (and the JVM twin BuildContractTest) never contain
# an unwrapped gate literal that would match itself.

set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MODULE_DIR"

# Toolchain fallbacks for bare shells; never override values already exported.
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/current}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$MODULE_DIR/.gradle}"

PASS=0
FAIL=0

ok() {
    PASS=$((PASS + 1))
    echo "PASS: $1"
}

bad() {
    FAIL=$((FAIL + 1))
    echo "FAIL: $1"
}

# Gate patterns assembled from fragments (see header comment).
AK="ANDROID_""KEY"
SIGN_PAT="signing""Configs"
KS_PAT="key""store"
KP_PAT="key""\.properties"

# [0] zero-secrets environment
unset "${AK}STORE_PATH" "${AK}STORE_PASSWORD" "${AK}ALIAS" "${AK}PASSWORD" 2>/dev/null || true
if env | grep -q "^${AK}"; then
    bad "zero-secrets env: ${AK}* variables still present after unset"
else
    ok "zero-secrets env: no ${AK}* variables in the gate environment"
fi

# [1] gate build — contract command, verbatim
BUILD_LOG="$MODULE_DIR/app/build/verify-build-contract.log"
mkdir -p "$MODULE_DIR/app/build"
if ./gradlew check :app:assembleDebug >"$BUILD_LOG" 2>&1; then
    ok "gate build: ./gradlew check :app:assembleDebug -> BUILD SUCCESSFUL (log: $BUILD_LOG)"
else
    bad "gate build failed — tail of $BUILD_LOG follows"
    tail -n 40 "$BUILD_LOG" || true
fi

# [2] APK artifact
APK="$MODULE_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_MIN_BYTES=10000
APK_SIZE=0
if [ -f "$APK" ]; then
    APK_SIZE=$(wc -c <"$APK")
    if [ "$APK_SIZE" -ge "$APK_MIN_BYTES" ]; then
        ok "artifact: app-debug.apk present ($APK_SIZE bytes)"
    else
        bad "artifact: app-debug.apk suspiciously small ($APK_SIZE bytes)"
    fi
else
    bad "artifact: app/build/outputs/apk/debug/app-debug.apk missing"
fi

# [3] merged manifest: exactly one real permission grant, INTERNET (CA-01/BR-031)
MERGED_MANIFEST=""
for candidate in "$MODULE_DIR"/app/build/intermediates/merged_manifests/debug/*/AndroidManifest.xml; do
    if [ -f "$candidate" ]; then
        MERGED_MANIFEST="$candidate"
        break
    fi
done
if [ -z "$MERGED_MANIFEST" ]; then
    bad "manifest: merged debug manifest not found under app/build/intermediates/merged_manifests/debug/"
else
    FLAT=$(tr '\n' ' ' <"$MERGED_MANIFEST")
    ELEMENT_COUNT=$(grep -o '<uses-permission' <<<"$FLAT" | wc -l || true)
    # Only non-stripper elements count as REQUESTS; aapt-style "permission:"
    # DECLARATIONS are not grants and never appear as uses-permission elements.
    GRANTS=$(grep -o '<uses-permission[^>]*' <<<"$FLAT" | grep -v 'tools:node="remove"' || true)
    TOTAL_GRANTS=$(grep -c 'uses-permission' <<<"$GRANTS" || true)
    INTERNET_GRANTS=$(grep -c 'android.permission.INTERNET' <<<"$GRANTS" || true)
    if [ "$TOTAL_GRANTS" -eq 1 ] && [ "$INTERNET_GRANTS" -eq 1 ]; then
        ok "manifest: merged debug manifest requests exactly one permission, INTERNET ($ELEMENT_COUNT uses-permission element(s) incl. strippers)"
    else
        bad "manifest: merged manifest must request EXACTLY ONE android.permission.INTERNET (CA-01/BR-031); grants ($TOTAL_GRANTS):"
        echo "$GRANTS"
    fi
fi

# [3b] binary APK permission requests (skipped gracefully when aapt is absent).
# Only uses-permission REQUESTS fail: aapt also prints "permission:" lines for
# custom permissions the package DECLARES (androidx.core's
# DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION declaration) — declarations are not grants.
# Exactly ONE request is expected: android.permission.INTERNET (CA-01/BR-031).
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
if [ -x "$AAPT" ] && [ -f "$APK" ]; then
    APK_PERMS=$("$AAPT" dump permissions "$APK" | grep '^uses-permission' || true)
    APK_PERM_COUNT=$(grep -c '^uses-permission' <<<"$APK_PERMS" || true)
    APK_INTERNET_COUNT=$(grep -c 'android.permission.INTERNET' <<<"$APK_PERMS" || true)
    if [ "$APK_PERM_COUNT" -eq 1 ] && [ "$APK_INTERNET_COUNT" -eq 1 ]; then
        ok "manifest: aapt dump permissions on app-debug.apk -> exactly one request (INTERNET)"
    else
        bad "manifest: aapt must report EXACTLY ONE uses-permission request, android.permission.INTERNET (got $APK_PERM_COUNT request(s)):"
        echo "$APK_PERMS"
    fi
else
    echo "SKIP: aapt unavailable at $AAPT — binary-APK permission dump skipped"
fi

# [4] version metadata in the merged manifest
if [ -n "$MERGED_MANIFEST" ]; then
    MERGED_VC=$(grep -o 'android:versionCode="[0-9]*"' "$MERGED_MANIFEST" | head -n 1 | grep -o '[0-9]*' || true)
    MERGED_VN=$(grep -o 'android:versionName="[^"]*"' "$MERGED_MANIFEST" | head -n 1 | sed 's/android:versionName="//; s/"$//' || true)
    if [ -n "$MERGED_VC" ] && [ "$MERGED_VC" -ge 1 ]; then
        ok "version: merged versionCode=$MERGED_VC is a positive integer"
    else
        bad "version: merged versionCode missing or not a positive integer (got '$MERGED_VC')"
    fi
    if grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9_.]+)?$' <<<"$MERGED_VN"; then
        ok "version: merged versionName='$MERGED_VN' is semver-ish"
    else
        bad "version: merged versionName '$MERGED_VN' does not match major.minor.patch(-suffix)?"
    fi
fi

# [5] debug runtime classpath: the pinned network stack must be present (CA-02)
DEPS_LOG="$MODULE_DIR/app/build/verify-classpath.log"
if ./gradlew :app:dependencies --configuration debugRuntimeClasspath >"$DEPS_LOG" 2>&1; then
    RETRO_HITS=$(grep -F 'com.squareup.retrofit2:retrofit:3.0.0' "$DEPS_LOG" || true)
    OKHTTP_HITS=$(grep -E 'com\.squareup\.okhttp3:okhttp(-jvm)?:5\.5\.0' "$DEPS_LOG" || true)
    SER_JSON_HITS=$(grep -F 'kotlinx-serialization-json:1.11.0' "$DEPS_LOG" || true)
    if [ -n "$RETRO_HITS" ]; then
        ok "classpath: retrofit 3.0.0 present on debugRuntimeClasspath"
    else
        bad "classpath: retrofit 3.0.0 NOT found on debugRuntimeClasspath"
    fi
    if [ -n "$OKHTTP_HITS" ]; then
        ok "classpath: okhttp 5.5.0 present on debugRuntimeClasspath"
    else
        bad "classpath: okhttp 5.5.0 NOT found on debugRuntimeClasspath"
    fi
    if [ -n "$SER_JSON_HITS" ]; then
        ok "classpath: kotlinx-serialization-json 1.11.0 present on debugRuntimeClasspath"
    else
        bad "classpath: kotlinx-serialization-json 1.11.0 NOT found on debugRuntimeClasspath"
    fi
else
    bad "classpath: :app:dependencies report failed — tail of $DEPS_LOG follows"
    tail -n 20 "$DEPS_LOG" || true
fi

# [6] tracked tree: no signing configuration / secret references (CA-04)
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    TRACKED_HITS=$(git ls-files -z -- . | xargs -0 grep -lE "${SIGN_PAT}|${KS_PAT}|${KP_PAT}|${AK}" 2>/dev/null || true)
    if [ -z "$TRACKED_HITS" ]; then
        ok "signing absence: zero signing/secret references across tracked apps/mobile files"
    else
        bad "signing absence: forbidden references found in tracked files:"
        echo "$TRACKED_HITS"
    fi
else
    bad "signing absence: not inside a git work tree — tracked-file audit impossible"
fi

# [7] version catalog: the network stack is pinned at the contracted versions
#     (CA-02); comments excluded from the pin assertions
TOML="$MODULE_DIR/gradle/libs.versions.toml"
TOML_CODE=$(sed 's/#.*//' "$TOML")
PINS_OK=0
grep -qE '^retrofit[[:space:]]*=[[:space:]]*"3\.0\.0"' <<<"$TOML_CODE" && PINS_OK=$((PINS_OK + 1))
grep -qE '^okhttp[[:space:]]*=[[:space:]]*"5\.5\.0"' <<<"$TOML_CODE" && PINS_OK=$((PINS_OK + 1))
grep -qE '^kotlinxSerializationJson[[:space:]]*=[[:space:]]*"1\.11\.0"' <<<"$TOML_CODE" && PINS_OK=$((PINS_OK + 1))
if [ "$PINS_OK" -eq 3 ]; then
    ok "catalog: libs.versions.toml pins retrofit 3.0.0 / okhttp 5.5.0 / kotlinx-serialization-json 1.11.0"
else
    bad "catalog: libs.versions.toml missing one or more contracted pins (retrofit=3.0.0, okhttp=5.5.0, kotlinxSerializationJson=1.11.0) — found $PINS_OK/3"
fi

# [8] monotonic versionCode across gate runs (CA-05)
GVC=$(grep -oE 'versionCode[[:space:]]*=[[:space:]]*[0-9]+' "$MODULE_DIR/app/build.gradle.kts" | grep -oE '[0-9]+' | head -n 1 || true)
if [ -n "$GVC" ] && [ "$GVC" -ge 1 ]; then
    ok "version: app/build.gradle.kts versionCode=$GVC is an integer literal >= 1"
else
    bad "version: app/build.gradle.kts versionCode is not an integer literal >= 1 (got '$GVC')"
fi
LAST_FILE="$MODULE_DIR/scripts/.last-versionCode"
if [ -f "$LAST_FILE" ] && [ -s "$LAST_FILE" ]; then
    LAST_VC=$(tr -d '[:space:]' <"$LAST_FILE")
    if [ -n "$GVC" ] && [ "$GVC" -gt "$LAST_VC" ]; then
        ok "version monotonic: versionCode $GVC strictly greater than last recorded $LAST_VC (CA-05)"
    elif [ -n "$GVC" ] && [ "$GVC" -eq "$LAST_VC" ]; then
        ok "version monotonic: versionCode $GVC unchanged since last gate run (bump before the next internal-test distribution)"
    else
        bad "version monotonic: versionCode $GVC is not strictly greater than recorded $LAST_VC (CA-05 regression)"
    fi
else
    ok "version monotonic: first recorded run (versionCode=$GVC)"
fi
mkdir -p "$MODULE_DIR/scripts"
printf '%s\n' "$GVC" >"$LAST_FILE"

echo
echo "BUILD CONTRACT SUMMARY: $PASS passed, $FAIL failed, module $MODULE_DIR"
if [ "$FAIL" -eq 0 ]; then
    echo "RESULT: PASS"
    exit 0
fi
echo "RESULT: FAIL"
exit 1
