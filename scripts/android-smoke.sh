#!/usr/bin/env bash
# Device smoke test on an Android emulator (run by CI inside reactivecircus/android-emulator-runner).
# Requires: API on host :3000 (reachable as 10.0.2.2 from the emulator), DATABASE_URL, an installed adb.
set -euo pipefail
# Every adb call is bounded so a hung device command fails fast instead of stalling the job.
ADB_BIN=$(type -P adb)
adb() { timeout "${ADB_TIMEOUT:-60}" "$ADB_BIN" "$@"; }
PKG=com.golgekuklaci.game
APK=apps/game/android/app/build/outputs/apk/debug/app-debug.apk
OUT=test-results/android
mkdir -p "$OUT"
users() { psql "$DATABASE_URL" -tAc 'select count(*) from "User"'; }
runs() { psql "$DATABASE_URL" -tAc 'select count(*) from "Run"'; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
alive() { adb shell pidof "$PKG" >/dev/null; }

echo "== install"; ADB_TIMEOUT=180 adb install -r "$APK"
U0=$(users); R0=$(runs)
echo "== launch"; adb shell am start -n "$PKG/.MainActivity"
for i in $(seq 1 30); do [ "$(users)" -gt "$U0" ] && break; sleep 3; done
shot 01-launch; alive
U1=$(users); echo "users before=$U0 after=$U1"
[ "$U1" -gt "$U0" ] || { echo "FAIL: app did not create a guest account through the API"; exit 1; }

echo "== onboarding → play with real touches → server-verified result (Playwright drives the WebView)"
OUT="$OUT" timeout 420 node scripts/android-webview-e2e.mjs | tee "$OUT/webview-e2e.txt"
F1=$(psql "$DATABASE_URL" -tAc "select count(*) from \"Run\" where status='FINISHED'")
R1=$(runs); echo "runs before=$R0 after=$R1 finished=$F1" | tee "$OUT/runs.txt"
[ "$F1" -ge 1 ] || { echo "FAIL: no server-verified run from the device"; exit 1; }

echo "== haptics reached the Android vibrator service?"
adb shell dumpsys vibrator_manager > "$OUT/vibrator.txt" 2>&1 || adb shell dumpsys vibrator > "$OUT/vibrator.txt" 2>&1 || true
grep -c "$PKG" "$OUT/vibrator.txt" | tee "$OUT/vibrations-count.txt" || true

echo "== lifecycle: background → foreground"
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell am start -n "$PKG/.MainActivity"; sleep 5; alive; shot 04-resumed

echo "== orientation lock: force landscape rotation"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1; sleep 4; shot 05-rotated
adb shell dumpsys activity activities | grep -iE "requestedOrientation|screenOrientation" | head -5 > "$OUT/orientation.txt" || true
adb shell settings put system user_rotation 0

echo "== secure storage: JWT must not be stored in plain shared_prefs"
: > "$OUT/shared_prefs.txt"
for f in $(ADB_TIMEOUT=15 adb shell "run-as $PKG ls shared_prefs" 2>/dev/null | tr -d '\r'); do
  ADB_TIMEOUT=15 adb exec-out "run-as $PKG cat shared_prefs/$f" >> "$OUT/shared_prefs.txt" 2>/dev/null || true
done
echo "shared_prefs bytes: $(wc -c < "$OUT/shared_prefs.txt")"
if grep -q 'eyJhbGci' "$OUT/shared_prefs.txt"; then echo "FAIL: plaintext JWT in shared_prefs"; exit 1; fi

echo "== deep link: golgekuklaci://duel/<code> opens the app's duel invite flow"
adb shell am start -a android.intent.action.VIEW -d "golgekuklaci://duel/TestInvite01" "$PKG" | tee "$OUT/deeplink.txt"
sleep 6; shot 06-deeplink; alive
! grep -qi "error" "$OUT/deeplink.txt"

echo "== frame stats"
adb shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt"
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile" "$OUT/gfxinfo.txt" || true

echo "== crash check"
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION" -A3 "$OUT/logcat.txt" | grep -q "$PKG"; then echo "FAIL: crash in logcat"; exit 1; fi
alive
echo "ANDROID SMOKE PASS"
