#!/usr/bin/env bash
# Build the Capacitor iOS app for the simulator, install, launch and screenshot it (CI on macOS).
# With TLS_CERT set (pinned build with VITE_PIN_SELFTEST), also checks ATS certificate pinning:
# the pinned host must answer, the host pinned to a wrong key must be refused.
set -euo pipefail
OUT=test-results/ios
mkdir -p "$OUT"
OUT_ABS=$(cd "$OUT" && pwd)
cd apps/game/ios/App
xcodebuild -workspace App.xcworkspace -scheme App -configuration Debug -sdk iphonesimulator \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO | tail -5
APP=build/Build/Products/Debug-iphonesimulator/App.app
test -d "$APP"
DEVICE=$(xcrun simctl list devices available -j | python3 -c '
import json,sys
d=json.load(sys.stdin)["devices"]
for rt,devs in d.items():
    if "iOS" in rt:
        for x in devs:
            if x["name"].startswith("iPhone"): print(x["udid"]); sys.exit()')
echo "simulator: $DEVICE"
xcrun simctl boot "$DEVICE" || true
xcrun simctl bootstatus "$DEVICE" -b
xcrun simctl install "$DEVICE" "$APP"
if [ -n "${TLS_CERT:-}" ]; then xcrun simctl keychain "$DEVICE" add-root-cert "$TLS_CERT"; fi
SIMCTL_CHILD_NSUnbufferedIO=YES xcrun simctl launch --terminate-running-process --stdout="$OUT_ABS/app-stdout.txt" --stderr="$OUT_ABS/app-stderr.txt" "$DEVICE" com.golgekuklaci.game
sleep 25
xcrun simctl io "$DEVICE" screenshot "../../../../$OUT/01-launch.png"
xcrun simctl spawn "$DEVICE" launchctl list | grep -i golgekuklaci | tee "../../../../$OUT/process.txt"
if [ -n "${TLS_CERT:-}" ]; then
  # The app reports its results to the pinned host; the CI TLS proxy logs every request line (TLS_PROXY_LOG).
  for i in $(seq 1 45); do grep -q "__pin_selftest" "$TLS_PROXY_LOG" 2>/dev/null && break; sleep 2; done
  { grep -h "PIN_SELFTEST" "$OUT_ABS"/app-std*.txt || true; grep "golge" "$TLS_PROXY_LOG" || true; } | tee "$OUT_ABS/pinning.txt"
  grep -q "__pin_selftest.*golge-pin.test -> ok " "$OUT_ABS/pinning.txt" || { echo "FAIL: pinned host not reachable"; exit 1; }
  grep -q "__pin_selftest.*golge-badpin.test -> error" "$OUT_ABS/pinning.txt" || { echo "FAIL: wrong-pin host was not refused"; exit 1; }
  if grep -q "^[A-Z]* golge-badpin.test" "$OUT_ABS/pinning.txt"; then echo "FAIL: a request reached the wrong-pin host"; exit 1; fi
  echo "iOS certificate pinning: PASS"
fi
echo "IOS SMOKE PASS"
