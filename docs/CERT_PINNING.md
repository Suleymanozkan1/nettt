# Certificate pinning

Android and iOS builds can pin the API host's public key. The pins are generated at build time, so no key material lives in the repository.

## How it works

- `apps/game/scripts/network-config.mjs` (run by `pnpm cap:sync`) writes `android/app/src/main/res/xml/network_security_config.xml` and the iOS `NSAppTransportSecurity → NSPinnedDomains` block in `ios/App/App/Info.plist` from the build environment:
  - `PIN_DOMAINS`: hosts to pin, comma-separated (e.g. `api.example.com`)
  - `PIN_SHA256`: base64 SHA-256 SPKI pins. Give at least two: the current key and a backup key kept offline.
  - `PIN_KIND`: `leaf` (default) or `ca`. It says whether the pins are SPKI hashes of the server certificate or of a CA in its chain. Android matches any certificate in the chain. iOS has to be told (`NSPinnedLeafIdentities` vs `NSPinnedCAIdentities`), so the kind is explicit.
  - `PIN_EXPIRES`: optional `YYYY-MM-DD`, **Android only**. After this date Android stops enforcing the pins, so an outdated app cannot be locked out forever. iOS pins have no expiry, so the script refuses `PIN_EXPIRES` while generating iOS pins. Set `PIN_IOS=0` to pin Android only.
- Android WebView does not apply pins, so pinned builds route the app's `fetch`/XHR through the native stack (`CapacitorHttp`, enabled automatically when `PIN_DOMAINS` is set). There, Android enforces the pins.
- Cleartext HTTP is disabled in every build except the CI emulator build (`CAP_DEV_CLEARTEXT=1`).

Compute a pin from a certificate:

```bash
openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
```

Pin the key of your own certificate (Caddy keeps the same key on renewal only if you configure it to). Alternatively, pin the intermediate CA's key and accept that the CA can change.

## Verified

The CI `android-device` job serves the API over HTTPS with a self-signed certificate on two addresses and builds the app with:
- the correct pin for `10.0.2.2`
- a wrong pin for `127.0.0.1`

On the emulator, the whole online smoke (guest sign-in, show, server-verified result) runs over the pinned host, and the request to the wrong-pin host is refused with a pinning error (`test-results/android/pinning.txt`).

**iOS (CI `ios` job):**
- Two host names (`golge-pin.test` with the correct pin, `golge-badpin.test` with a wrong one) point to the same HTTPS proxy, which serves the same certificate. That certificate is trusted in the simulator.
- A self-test build calls both through the native stack (URLSession, which enforces ATS pins).
- The pinned host answers.
- For the wrong-pin host, the proxy log shows that the app reached it (TLS SNI) but no HTTP request ever arrived, and the app reports a TLS error.

## Limits

- The realtime WebSocket (duel) is opened by the WebView, so it is protected by TLS but not pinned.
