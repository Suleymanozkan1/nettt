#!/usr/bin/env bash
# Release-APK smoke test on an Android emulator (run in CI by reactivecircus/android-emulator-runner).
# Drives the real UI through the accessibility tree: onboarding -> demo book is processed in the background
# (WorkManager + Hilt worker, R8-minified) -> library -> quick summary. Fails on any crash in logcat.
set -euo pipefail
APK=${APK:-app-x86_64-release.apk}
PKG=com.bookmind.app
OUT=${OUT:-test-results/bookmind-android}
mkdir -p "$OUT"
ADB_BIN=$(type -P adb)
adb() { timeout "${ADB_TIMEOUT:-60}" "$ADB_BIN" "$@"; }
trap 'rc=$?; adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true; adb exec-out screencap -p > "$OUT/last.png" 2>/dev/null || true; exit $rc' EXIT

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || true; }
# Prints "x y" of the node whose text or content-desc equals $1, else the first that contains it, else nothing.
find_text() {
  dump
  python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(0)
nodes = list(root.iter("node"))
labels = lambda n: ((n.get("text") or ""), (n.get("content-desc") or ""))
target = sys.argv[2]
# Note: an Element without children is falsy, so compare with None explicitly.
match = next((n for n in nodes if target in labels(n)), None)
if match is None:
    match = next((n for n in nodes if any(target in l for l in labels(n))), None)
if match is not None:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", match.get("bounds")))
    print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}
wait_text() { # wait_text "label" [seconds] [quiet]
  local deadline=$((SECONDS + ${2:-60}))
  while [ $SECONDS -lt $deadline ]; do [ -n "$(find_text "$1")" ] && return 0; sleep 2; done
  [ -n "${3:-}" ] || echo "FAIL: '$1' did not appear"
  return 1
}
tap_text() { wait_text "$1" "${2:-60}"; local xy; xy=$(find_text "$1"); adb shell input tap $xy; sleep 1; }
# Like tap_text, but swipes the content up until the label is on screen (small emulator screens).
scroll_tap_text() {
  local i
  for i in 1 2 3 4 5 6; do
    if [ -n "$(find_text "$1")" ]; then tap_text "$1" 5; return 0; fi
    local size w h
    size=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); w=${size%x*}; h=${size#*x}
    adb shell input swipe $((w / 2)) $((h * 7 / 10)) $((w / 2)) $((h * 3 / 10)) 300; sleep 1
  done
  tap_text "$1" 10
}
no_crash() { ! adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" ; }

for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
adb shell settings put system system_locales en-US >/dev/null 2>&1 || true
echo "== install"; ADB_TIMEOUT=240 adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
echo "== cold start"; adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/startup.txt"

echo "== onboarding: language first (the app starts in Turkish), then switch to English"
wait_text "Türkçe" 90
adb exec-out screencap -p > "$OUT/00-language.png"
# Both options must fit on the screen; scrolling here would mean the layout regressed on small phones.
tap_text "English" 20
# Choosing a language recreates the activity in that language.
wait_text "Next" 30
tap_text "Next"; tap_text "Next"; tap_text "Next"; tap_text "Next"
tap_text "Start"
# "Start reviewing" appears once the demo book's cards exist; otherwise the Today screen title is enough.
wait_text "Start reviewing" 60 quiet || wait_text "Today" 30
adb exec-out screencap -p > "$OUT/01-home.png"

echo "== demo book (English edition) processed in the background (release build, WorkManager)"
tap_text "Library"
wait_text "The Science of Learning" 60
deadline=$((SECONDS + 180))
while [ $SECONDS -lt $deadline ]; do
  dump
  if ! grep -qE 'text="(Queued|Extracting text|Analysing)"' "$OUT/ui.xml"; then break; fi
  sleep 5
done
grep -qE 'text="(Failed|OCR needed)"' "$OUT/ui.xml" && { echo "FAIL: processing failed"; exit 1; }
adb exec-out screencap -p > "$OUT/02-library.png"

echo "== book detail and on-device quick summary, all in English"
tap_text "The Science of Learning"
wait_text "Review" 60
scroll_tap_text "Quick summary"
tap_text "Generate"
wait_text "Key idea" 90
dump
if grep -qE 'text="[^"]*(Temel fikir|Hızlı özet|Öne çıkan)' "$OUT/ui.xml"; then echo "FAIL: Turkish text on an English screen"; exit 1; fi
adb exec-out screencap -p > "$OUT/03-summary.png"

echo "== crash check"
no_crash
adb shell pidof "$PKG" >/dev/null
echo "BookMind release smoke test passed"
