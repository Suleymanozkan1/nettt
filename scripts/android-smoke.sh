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
tap_text() { # tap the centre of the first accessibility node whose text/desc matches $1 (WebView exposes DOM nodes)
  ADB_TIMEOUT=25 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || return 1
  python3 - "$1" "$OUT/ui.xml" <<'PY' | xargs -r adb shell input tap
import re, sys
needle, path = sys.argv[1], sys.argv[2]
xml = open(path, encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    if needle in n:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
}

echo "== install"; ADB_TIMEOUT=180 adb install -r "$APK"
U0=$(users); R0=$(runs)
echo "== launch"; adb shell am start -n "$PKG/.MainActivity"
for i in $(seq 1 30); do [ "$(users)" -gt "$U0" ] && break; sleep 3; done
shot 01-launch; alive
U1=$(users); echo "users before=$U0 after=$U1"
[ "$U1" -gt "$U0" ] || { echo "FAIL: app did not create a guest account through the API"; exit 1; }

echo "== onboarding + play (best effort via accessibility tree)"
tap_text 'Sahneye' && sleep 3 || true
shot 02-after-onboarding
tap_text 'GÖSTERİ' && sleep 4 || true
for i in 1 2 3 4 5 6; do adb shell input tap 540 1200; sleep 1.2; done
shot 03-playing
R1=$(runs); echo "runs before=$R0 after=$R1" | tee "$OUT/runs.txt"

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
adb shell run-as "$PKG" sh -c 'cat shared_prefs/*.xml 2>/dev/null' > "$OUT/shared_prefs.txt" || true
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
