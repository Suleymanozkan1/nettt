import { Capacitor } from '@capacitor/core';
import { PushNotifications } from '@capacitor/push-notifications';

/**
 * Server push (FCM; iOS via APNs through FCM). Only in builds made with VITE_PUSH=1, which also need the
 * Firebase config files (google-services.json / GoogleService-Info.plist); see docs/PUSH.md.
 * Returns true once the device token has been handed to `register`.
 */
export const PUSH_ENABLED = import.meta.env.VITE_PUSH === '1';

export async function enablePush(register: (token: string, platform: 'android' | 'ios') => Promise<unknown>): Promise<boolean> {
  if (!PUSH_ENABLED || !Capacitor.isNativePlatform()) return false;
  const perm = await PushNotifications.requestPermissions();
  if (perm.receive !== 'granted') return false;
  const token = new Promise<string>((resolve, reject) => {
    void PushNotifications.addListener('registration', (t) => resolve(t.value));
    void PushNotifications.addListener('registrationError', (e) => reject(new Error(e.error)));
  });
  await PushNotifications.register();
  await register(await token, Capacitor.getPlatform() === 'ios' ? 'ios' : 'android');
  return true;
}
