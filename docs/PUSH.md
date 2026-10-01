# Push notifications (FCM / APNs)

Push is **built in but switched off** until the owner adds Firebase credentials. No credentials are in the repository.

## What already works

| Part | Where | Verified by |
|---|---|---|
| Device token registration `POST /me/push-token`, removal `DELETE /me/push-token`, tokens dropped on "log out of all devices" | `apps/api/src/routes/profile.ts`, `PushToken` table | `apps/api/test/push.test.ts` |
| FCM HTTP v1 sender (service-account JWT → OAuth token → `messages:send`), dead tokens removed | `apps/api/src/push.ts` | same test, against a mock Google endpoint that checks the RS256 signature |
| Daily reminder: only players who turned reminders on and have not claimed today's reward, at most once per device per day | `runDailyReminders()`, scheduled in `apps/api/src/server.ts` at `PUSH_REMINDER_HOUR_UTC` (default 16 = 19:00 in Türkiye) | same test |
| Client registration (Capacitor `@capacitor/push-notifications`), only in builds made with `VITE_PUSH=1` | `apps/game/src/lib/push.ts` | — (needs Firebase) |
| On-device daily reminder (local notification, no server) | `apps/game/src/lib/reminders.ts` | CI Android emulator: notification posted (`dumpsys notification`) |

Metric: `golge_push_sent_total{result="ok|invalid_token|error"}`.

## Turning it on

1. Create a Firebase project and add the Android app `com.golgekuklaci.game` (and the iOS app with the same bundle id).
2. **Android:** download `google-services.json` to `apps/game/android/app/`. The Gradle build applies the Google Services plugin only when this file exists.
3. **iOS:** upload an APNs authentication key (.p8) in Firebase → Project settings → Cloud Messaging. Add `GoogleService-Info.plist` to the Xcode project, and enable the *Push Notifications* capability. This needs an Apple Developer account and signing.
4. **Server:** Project settings → Service accounts → *Generate new private key*. Put the JSON in the API environment as `FCM_SERVICE_ACCOUNT` (one line). Optionally set `PUSH_REMINDER_HOUR_UTC`.
5. Build the app with `VITE_PUSH=1`.

Without `FCM_SERVICE_ACCOUNT` the API logs `push notifications disabled`, still stores tokens, and sends nothing.

The offline edition (`VITE_BACKEND=local`) never registers for push; it uses only the on-device daily reminder.
