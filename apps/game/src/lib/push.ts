import { Capacitor, type PluginListenerHandle } from '@capacitor/core';
import { PushNotifications } from '@capacitor/push-notifications';

/**
 * Server push (FCM; iOS via APNs through FCM). Only in builds made with VITE_PUSH=1, which also need the
 * Firebase config files (google-services.json / GoogleService-Info.plist); see docs/PUSH.md.
 */
export const PUSH_ENABLED = import.meta.env.VITE_PUSH === '1';
const REGISTER_TIMEOUT_MS = 20_000;

let pending: Promise<boolean> | null = null;

/** Registers this device once per app session and hands the token to `register`. Resolves false if unavailable. */
export function enablePush(register: (token: string, platform: 'android' | 'ios') => Promise<unknown>): Promise<boolean> {
  if (!PUSH_ENABLED || !Capacitor.isNativePlatform()) return Promise.resolve(false);
  pending ??= (async () => {
    const perm = await PushNotifications.requestPermissions();
    if (perm.receive !== 'granted') return false;
    const handles: Promise<PluginListenerHandle>[] = [];
    try {
      const token = new Promise<string>((resolve, reject) => {
        handles.push(PushNotifications.addListener('registration', (t) => resolve(t.value)));
        handles.push(PushNotifications.addListener('registrationError', (e) => reject(new Error(e.error))));
        setTimeout(() => reject(new Error('push registration timed out')), REGISTER_TIMEOUT_MS);
      });
      await PushNotifications.register();
      await register(await token, Capacitor.getPlatform() === 'ios' ? 'ios' : 'android');
      return true;
    } finally {
      for (const h of handles) void h.then((x) => x.remove());
    }
  })().then(
    (ok) => { if (!ok) pending = null; return ok; }, // denied/failed attempts may be retried later
    (e: unknown) => { pending = null; throw e; },
  );
  return pending;
}
