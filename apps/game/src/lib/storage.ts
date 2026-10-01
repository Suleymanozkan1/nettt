import { Capacitor } from '@capacitor/core';
import { SecureStorage } from '@aparajita/capacitor-secure-storage';

/**
 * Native: encrypted storage (Android Keystore / iOS Keychain) for the auth token and device id.
 * Web: localStorage, guarded for private mode.
 */
export const storage = {
  async get(key: string): Promise<string | null> {
    if (Capacitor.isNativePlatform()) {
      const v = await SecureStorage.get(key).catch(() => null);
      return typeof v === 'string' ? v : null;
    }
    try { return localStorage.getItem(key); } catch { return null; }
  },
  async set(key: string, value: string): Promise<void> {
    if (Capacitor.isNativePlatform()) { await SecureStorage.set(key, value); return; }
    try { localStorage.setItem(key, value); } catch { /* storage unavailable */ }
  },
  async remove(key: string): Promise<void> {
    if (Capacitor.isNativePlatform()) { await SecureStorage.remove(key).catch(() => undefined); return; }
    try { localStorage.removeItem(key); } catch { /* storage unavailable */ }
  },
};
