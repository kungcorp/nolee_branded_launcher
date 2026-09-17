#!/usr/bin/env bash
# One-command device setup for the Nolee Branded Launcher: install, grants, root, companions, kiosk.
#
#     android/tools/setup_device.sh [SERIAL]
#
# Nothing here happens automatically on install. WRITE_SECURE_SETTINGS and the runtime permissions are
# Android grants (pm grant); root and companion apps are Nolee Launcher grants (content call). All of them
# need AI agent access (ADB) on, and the Launcher ones need kiosk exited, so this script leaves kiosk first
# and re-enters it at the end. Re-run it after any uninstall/reinstall: a new install can get a new uid, and
# Android clears the grants.
set -euo pipefail
cd "$(dirname "$0")/.."

SERIAL="${1:-${ANDROID_SERIAL:-}}"
[ -n "$SERIAL" ] || { echo "Pass the owner's selected ADB serial." >&2; exit 1; }
ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
PKG=ai.nolee.brandedlauncher
URI=content://io.kungcorp.nolee.launcher.state
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
read -r -a COMPANIONS <<< "${NOLEE_COMPANIONS-ai.nolee.camera ai.nolee.files ai.nolee.gallery ai.nolee.phone ai.nolee.sms}"
for companion in "${COMPANIONS[@]}"; do
    [[ "$companion" =~ ^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$ ]] || { echo "Invalid companion package name" >&2; exit 1; }
done

# A Launcher call that must succeed. The provider answers ok=false with a reason rather than failing.
call() {
    local out
    out="$("${ADB[@]}" shell content call --uri "$URI" --method "$@")"
    echo "  $1 ${*:2}: $(echo "$out" | grep -oE 'ok=(true|false)|reason=[^,}]*' | tr '\n' ' ')"
    if ! echo "$out" | grep -q 'ok=true'; then
        echo "FAILED: $1 was refused (see the reason above)." >&2
        exit 1
    fi
}

[ -f "$APK" ] || { echo "Build first: $APK is missing." >&2; exit 1; }
for companion in "${COMPANIONS[@]}"; do
    "${ADB[@]}" shell pm path "$companion" | grep -q '^package:' || {
        echo "Install the owner-selected companion first: $companion" >&2; exit 1;
    }
done

echo "== leaving kiosk (Launcher grants need it off; refused harmlessly if none is running)"
"${ADB[@]}" shell content call --uri "$URI" --method exit_kiosk >/dev/null || true

echo "== installing $APK"
[ -f "$APK" ] || { echo "Build first: $APK is missing." >&2; exit 1; }
"${ADB[@]}" install -r "$APK"

echo "== Android grants"
# WRITE_SECURE_SETTINGS: vitals measurement mode. BODY_SENSORS: the vitals sensors themselves (without it
# Android refuses to enable them and no reading ever arrives). RECORD_AUDIO: Ask AI voice commands.
# Location: Wi-Fi scan and Bluetooth discovery return nothing on API 28 without it, and location services
# must be on too.
for permission in WRITE_SECURE_SETTINGS BODY_SENSORS RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    "${ADB[@]}" shell pm grant "$PKG" "android.permission.$permission"
    echo "  granted $permission"
done
# Brightness and adaptive mode.
"${ADB[@]}" shell appops set "$PKG" WRITE_SETTINGS allow
echo "  allowed WRITE_SETTINGS"
"${ADB[@]}" shell settings put secure location_mode 3
echo "  location services on"

echo "== Nolee Launcher grants"
# Root: the su fallback for Wi-Fi, Bluetooth and brightness when Android refuses the public API.
call set_root_access --arg "$PKG" --extra enabled:b:true
# Companions: the apps the home drum and voice commands open. Strict kiosk blocks any app not approved here.
for companion in "${COMPANIONS[@]}"; do
    call set_companion --arg "$companion" --extra enabled:b:true
done

echo "== primary app and kiosk"
call set_primary_app --arg "$PKG"
call request_kiosk --arg "$PKG"
echo "Done."
