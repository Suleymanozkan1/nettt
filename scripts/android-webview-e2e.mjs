// Drives the real app inside the Android emulator (CI): Capacitor WebView via Playwright's Android support,
// OS-level touches on the stage via adb. Proves onboarding → play → verified result on a device, plus
// safe-area insets, a posted local notification, frame stats and (online build) certificate pinning.
//   OFFLINE=1    the offline edition (no server; results computed on the device)
//   PIN_CHECK=1  pinned host must work, a host pinned to a wrong key must be refused
import { _android as android } from '@playwright/test';
import { writeFileSync } from 'node:fs';

const PKG = 'com.golgekuklaci.game';
const OUT = process.env.OUT ?? 'test-results/android';
const OFFLINE = process.env.OFFLINE === '1';
const tag = OFFLINE ? 'offline-' : '';
const [device] = await android.devices();
if (!device) throw new Error('no Android device');
console.log('device', device.model(), device.serial());

const webview = await device.webView({ pkg: PKG }, { timeout: 60_000 });
const page = await webview.page();
const onboard = page.getByTestId('onboard');
await onboard.or(page.getByTestId('play')).waitFor({ timeout: 60_000 });
if (await onboard.isVisible()) {
  await page.getByTestId('name-input').fill('Emülatör');
  await onboard.click();
}
await page.getByTestId('play').waitFor({ timeout: 30_000 });
// Reload with the read-only debug hook so we can count taps the game actually accepted.
await page.goto(new URL('/?debug=1', page.url()).toString());
await page.getByTestId('play').waitFor({ timeout: 60_000 });
if (OFFLINE) await page.getByTestId('offline-edition').waitFor({ timeout: 10_000 });
await device.screenshot({ path: `${OUT}/${tag}10-home.png` });

// Safe area: the native shell passes system-bar/cutout insets to CSS (edge-to-edge layout).
await page.waitForFunction(() => window.__stage.insets()[0] !== '0px', null, { timeout: 15_000 });
const insets = await page.evaluate(() => window.__stage.insets());
console.log('safe-area insets (top, bottom, left, right):', insets.join(' '));
if (parseFloat(insets[0]) <= 0) throw new Error('safe-area top inset not applied');

// Frame stats only for the show itself.
await device.shell(`dumpsys gfxinfo ${PKG} reset`);
// The play button pulses forever (never 'stable' for Playwright); force the click.
await page.getByTestId('play').click({ force: true });
await page.getByTestId('pause').waitFor({ timeout: 30_000 });

// Real touches through the Android input system.
const size = /(\d+)x(\d+)/.exec((await device.shell('wm size')).toString());
const [w, h] = size ? [Number(size[1]), Number(size[2])] : [320, 640];
for (let i = 0; i < 6; i++) {
  await device.shell(`input tap ${Math.round(w / 2)} ${Math.round(h * 0.62)}`); // OS-level touch via adb
  await page.waitForTimeout(1100);
}
await device.screenshot({ path: `${OUT}/${tag}11-playing.png` });
const state = await page.evaluate(() => window.__stage.state());
console.log('after OS touches:', JSON.stringify(state));
if (!state || state.taps < 1) throw new Error('no OS touch reached the game');

// Blind taps usually miss: the show may already be over (lives 0). Otherwise end it from the pause menu.
if (state.state === 'dead') {
  await page.getByTestId('results').click({ force: true });
} else {
  await page.getByTestId('pause').click({ force: true });
  await page.getByRole('button', { name: /Gösteriyi bitir/ }).click({ force: true });
}
await page.getByTestId('verified').waitFor({ timeout: 30_000 });
const final = await page.getByTestId('final-score').textContent();
console.log(`${OFFLINE ? 'device-verified' : 'server-verified'} final score:`, final);
await device.screenshot({ path: `${OUT}/${tag}12-results.png` });
writeFileSync(`${OUT}/${tag}gfxinfo-show.txt`, (await device.shell(`dumpsys gfxinfo ${PKG}`)).toString());

// Local notification: schedule a test reminder and check Android actually posted it.
if (!(await page.evaluate(() => window.__stage.testReminder(3)))) throw new Error('notification permission denied');
let posted = '';
for (let i = 0; i < 10 && !posted.includes('Test hatırlatması'); i++) {
  await page.waitForTimeout(1500);
  posted = (await device.shell('dumpsys notification --noredact')).toString();
}
writeFileSync(`${OUT}/${tag}notification.txt`, posted.split('\n').filter((l) => l.includes(PKG) || l.includes('hatırlat')).join('\n'));
if (!posted.includes('Test hatırlatması')) throw new Error('local notification was not posted');
console.log('local notification posted');
await device.screenshot({ path: `${OUT}/${tag}13-notification.png` });

if (process.env.PIN_CHECK === '1') {
  // Both hosts serve the same certificate: only the pin decides.
  const probe = (url) => page.evaluate(async (u) => {
    try { const r = await fetch(u); return `ok ${r.status}`; } catch (e) { return `error ${e instanceof Error ? e.message : String(e)}`; }
  }, url);
  const good = await probe('https://10.0.2.2:8443/health');
  const bad = await probe('https://127.0.0.1:8443/health');
  console.log('pinned host:', good, '| wrong-pin host:', bad);
  writeFileSync(`${OUT}/pinning.txt`, `pinned: ${good}\nwrong pin: ${bad}\n`);
  if (!good.startsWith('ok 200')) throw new Error('pinned host should be reachable');
  if (!/^error .*(pin|certificate|ssl)/i.test(bad)) throw new Error(`wrong-pin host must be refused by pinning: ${bad}`);
}
await device.close();
console.log(`ANDROID WEBVIEW E2E PASS${OFFLINE ? ' (offline edition)' : ''}`);
