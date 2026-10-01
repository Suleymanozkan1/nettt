#!/usr/bin/env bash
# Device smoke test on an Android emulator (run by CI inside reactivecircus/android-emulator-runner).
# Requires: API on host :3000 behind the CI TLS proxy on :8443 (reachable as 10.0.2.2 from the emulator),
# DATABASE_URL, adb. APK = online build (pinned); OFFLINE_APK = offline edition (no server).
set -euo pipefail
# Every adb call is bounded so a hung device command fails fast instead of stalling the job.
ADB_BIN=$(type -P adb)
adb() { timeout "${ADB_TIMEOUT:-60}" "$ADB_BIN" "$@"; }
PKG=com.golgekuklaci.game
APK=${APK:-online.apk}
OFFLINE_APK=${OFFLINE_APK:-offline.apk}
OUT=test-results/android
mkdir -p "$OUT"
users() { psql "$DATABASE_URL" -tAc 'select count(*) from "User"'; }
runs() { psql "$DATABASE_URL" -tAc 'select count(*) from "Run"'; }
finished() { psql "$DATABASE_URL" -tAc "select count(*) from \"Run\" where status='FINISHED'"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
alive() { adb shell pidof "$PKG" >/dev/null; }
# On any failure keep the device log, so a failed run is diagnosable from the artifact.
trap 'rc=$?; [ $rc -ne 0 ] && adb logcat -d > "$OUT/logcat-failure.txt" 2>/dev/null; exit $rc' EXIT

echo "== install"; ADB_TIMEOUT=180 adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb reverse tcp:8443 tcp:8443 # emulator 127.0.0.1:8443 → host TLS proxy (the wrong-pin test host)
U0=$(users); R0=$(runs); F0=$(finished)
# A freshly booted emulator can still be settling (launcher, package manager); wait until it is idle.
for i in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && adb shell dumpsys window | grep -q mCurrentFocus && break; sleep 2; done
echo "== cold start"; adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/startup.txt"
for i in $(seq 1 30); do
  [ "$(users)" -gt "$U0" ] && break
  # If the launch was swallowed by a still-settling emulator (app not running), launch once more.
  # A crash is still caught: the logcat FATAL check below covers the whole run.
  if [ "$i" = 10 ] && ! alive; then echo "app not running after launch; relaunching"; adb shell am start -n "$PKG/.MainActivity"; fi
  sleep 3
done
shot 01-launch; alive
U1=$(users); echo "users before=$U0 after=$U1"
[ "$U1" -gt "$U0" ] || { echo "FAIL: app did not create a guest account through the API"; exit 1; }

echo "== onboarding → play with real touches → server-verified result (Playwright drives the WebView)"
OUT="$OUT" PIN_CHECK=1 timeout 420 node scripts/android-webview-e2e.mjs | tee "$OUT/webview-e2e.txt"
F1=$(finished)
R1=$(runs); echo "runs before=$R0 after=$R1 finished before=$F0 after=$F1" | tee "$OUT/runs.txt"
[ "$F1" -gt "$F0" ] || { echo "FAIL: this test's run was not server-verified (FINISHED)"; exit 1; }
adb logcat -d | grep -iE "pin verification|certificate pinning|SSLPeerUnverified" | head -5 > "$OUT/pinning-logcat.txt" || true

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
# The inspection itself must succeed: an unreadable directory or file is a failure, not a pass.
PREFS=$(ADB_TIMEOUT=15 adb shell "run-as $PKG ls shared_prefs" | tr -d '\r') || { echo "FAIL: cannot list shared_prefs"; exit 1; }
for f in $PREFS; do
  ADB_TIMEOUT=15 adb exec-out "run-as $PKG cat shared_prefs/$f" >> "$OUT/shared_prefs.txt" || { echo "FAIL: cannot read shared_prefs/$f"; exit 1; }
done
echo "shared_prefs bytes: $(wc -c < "$OUT/shared_prefs.txt")"
if grep -q 'eyJhbGci' "$OUT/shared_prefs.txt"; then echo "FAIL: plaintext JWT in shared_prefs"; exit 1; fi

echo "== deep link: golgekuklaci://duel/<code> opens the app's duel invite flow"
adb shell am start -a android.intent.action.VIEW -d "golgekuklaci://duel/TestInvite01" "$PKG" | tee "$OUT/deeplink.txt"
sleep 6; shot 06-deeplink; alive
if grep -qi "error" "$OUT/deeplink.txt"; then echo "FAIL: deep link not handled"; exit 1; fi

echo "== performance (emulator, software GPU): startup, frame times during the show, memory"
grep -E "TotalTime|WaitTime" "$OUT/startup.txt" || true
grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile" "$OUT/gfxinfo-show.txt" | tee "$OUT/perf-frames.txt" || true
adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo.txt"
grep -E "TOTAL PSS|TOTAL:" "$OUT/meminfo.txt" | head -2 | tee "$OUT/perf-memory.txt" || true

echo "== crash check"
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION" -A3 "$OUT/logcat.txt" | grep -q "$PKG"; then echo "FAIL: crash in logcat"; exit 1; fi
alive

echo "== offline edition: separate APK, plays with no server at all"
adb uninstall "$PKG" >/dev/null; adb logcat -c
ADB_TIMEOUT=180 adb install "$OFFLINE_APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
# Package removal/install broadcasts make Play services clean up; give them time so they do not restart under us.
sleep 20
U2=$(users); R2=$(runs)
adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/offline-startup.txt"
# Retry once only if Android killed the app because a system provider it depends on (Play services fonts) died;
# any other failure is a real failure.
if ! OUT="$OUT" OFFLINE=1 timeout 420 node scripts/android-webview-e2e.mjs | tee "$OUT/offline-webview-e2e.txt"; then
  adb logcat -d | grep -E "Killing [0-9]+:$PKG/.*depends on provider .* in dying proc" | tee "$OUT/offline-killed-by-system.txt"
  [ -s "$OUT/offline-killed-by-system.txt" ] || { echo "FAIL: offline edition e2e"; exit 1; }
  echo "app was killed by a dying system provider; relaunching once"
  adb logcat -c; adb shell am start -W -n "$PKG/.MainActivity"
  OUT="$OUT" OFFLINE=1 timeout 420 node scripts/android-webview-e2e.mjs | tee "$OUT/offline-webview-e2e.txt"
fi
[ "$(users)" -eq "$U2" ] && [ "$(runs)" -eq "$R2" ] || { echo "FAIL: offline edition contacted the server"; exit 1; }
adb logcat -d > "$OUT/logcat-offline.txt"
if grep -E "FATAL EXCEPTION" -A3 "$OUT/logcat-offline.txt" | grep -q "$PKG"; then echo "FAIL: crash in logcat (offline)"; exit 1; fi
alive
echo "ANDROID SMOKE PASS"
