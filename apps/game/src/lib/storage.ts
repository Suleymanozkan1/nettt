import { Capacitor } from '@capacitor/core';
import { Preferences } from '@capacitor/preferences';

/** Native: Capacitor Preferences (app sandbox). Web: localStorage, guarded for private mode. */
export const storage = {
  async get(key: string): Promise<string | null> {
    if (Capacitor.isNativePlatform()) return (await Preferences.get({ key })).value;
    try { return localStorage.getItem(key); } catch { return null; }
  },
  async set(key: string, value: string): Promise<void> {
    if (Capacitor.isNativePlatform()) { await Preferences.set({ key, value }); return; }
    try { localStorage.setItem(key, value); } catch { /* storage unavailable */ }
  },
  async remove(key: string): Promise<void> {
    if (Capacitor.isNativePlatform()) { await Preferences.remove({ key }); return; }
    try { localStorage.removeItem(key); } catch { /* storage unavailable */ }
  },
};
