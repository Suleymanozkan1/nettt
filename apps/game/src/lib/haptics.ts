import { Capacitor } from '@capacitor/core';
import { Haptics, ImpactStyle, NotificationType } from '@capacitor/haptics';

let enabled = true;
export function setHapticsEnabled(on: boolean): void { enabled = on; }

function vibrate(ms: number | number[]): void {
  try { navigator.vibrate?.(ms); } catch { /* unsupported */ }
}

/** Native haptics on Android/iOS via Capacitor; navigator.vibrate fallback on mobile web. */
export const haptic = {
  light(): void {
    if (!enabled) return;
    if (Capacitor.isNativePlatform()) void Haptics.impact({ style: ImpactStyle.Light });
    else vibrate(10);
  },
  heavy(): void {
    if (!enabled) return;
    if (Capacitor.isNativePlatform()) void Haptics.impact({ style: ImpactStyle.Heavy });
    else vibrate(30);
  },
  fail(): void {
    if (!enabled) return;
    if (Capacitor.isNativePlatform()) void Haptics.notification({ type: NotificationType.Error });
    else vibrate([40, 40, 80]);
  },
};
