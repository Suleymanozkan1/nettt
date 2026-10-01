import { Capacitor } from '@capacitor/core';
import { LocalNotifications } from '@capacitor/local-notifications';

const REMINDER_ID = 1;

/**
 * Opt-in daily reminder (19:00 local time) using on-device local notifications — nothing is sent from a
 * server. Returns false when not supported (web) or permission is denied.
 */
export async function setDailyReminder(on: boolean): Promise<boolean> {
  if (!Capacitor.isNativePlatform()) return false;
  await LocalNotifications.cancel({ notifications: [{ id: REMINDER_ID }] });
  if (!on) return true;
  const perm = await LocalNotifications.requestPermissions();
  if (perm.display !== 'granted') return false;
  await LocalNotifications.schedule({
    notifications: [{
      id: REMINDER_ID,
      title: 'Gölge Kuklacı',
      body: 'Günlük ödülün ve yeni görevlerin hazır. Perde açılıyor!',
      schedule: { on: { hour: 19, minute: 0 }, allowWhileIdle: true },
    }],
  });
  return true;
}
