import type { CapacitorConfig } from '@capacitor/cli';

/**
 * CAP_DEV_CLEARTEXT=1 is only for emulator/CI builds that talk to a local http API (10.0.2.2).
 * Release builds keep cleartext and mixed content disabled and must use an HTTPS/WSS API.
 */
const devCleartext = process.env.CAP_DEV_CLEARTEXT === '1';
/**
 * Certificate pinning (PIN_DOMAINS + PIN_SHA256, see scripts/network-config.mjs) only applies to the native
 * network stack, so pinned builds route the app's fetch/XHR through CapacitorHttp.
 */
const pinned = !!process.env.PIN_DOMAINS;

const config: CapacitorConfig = {
  appId: 'com.golgekuklaci.game',
  appName: 'Gölge Kuklacı',
  webDir: 'dist',
  android: { allowMixedContent: devCleartext },
  server: devCleartext ? { cleartext: true } : undefined,
  plugins: { CapacitorHttp: { enabled: pinned } },
};

export default config;
