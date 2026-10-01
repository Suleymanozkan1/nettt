# Certificate pinning

Android release builds can pin the API host's public key. The pins are generated at build time, so no key material lives in the repository.

## How it works

- `apps/game/scripts/network-config.mjs` (run by `pnpm cap:sync`) writes `android/app/src/main/res/xml/network_security_config.xml` from the build environment:
  - `PIN_DOMAINS`: hosts to pin, comma-separated (e.g. `api.example.com`)
  - `PIN_SHA256`: base64 SHA-256 SPKI pins. Give at least two: the current key and a backup key kept offline.
  - `PIN_EXPIRES`: optional `YYYY-MM-DD`; after this date the pins stop being enforced, so an outdated app cannot be locked out forever.
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

## Limits

- The realtime WebSocket (duel) is opened by the WebView, so it is protected by TLS but not pinned.
- **iOS:** add `NSPinnedDomains` under `NSAppTransportSecurity` in `Info.plist` with the same SPKI hashes. This is not generated yet and has not been tested on iOS.
