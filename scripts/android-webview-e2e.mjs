// Drives the real app inside the Android emulator (CI): Capacitor WebView via Playwright's Android support,
// OS-level touches on the stage via adb. Proves onboarding → play → server-verified result on a device.
import { _android as android } from '@playwright/test';

const PKG = 'com.golgekuklaci.game';
const OUT = process.env.OUT ?? 'test-results/android';
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
await device.screenshot({ path: `${OUT}/10-home.png` });
await page.getByTestId('play').click();
await page.getByTestId('pause').waitFor({ timeout: 30_000 });

// Real touches through the Android input system.
const size = /(\d+)x(\d+)/.exec((await device.shell('wm size')).toString());
const [w, h] = size ? [Number(size[1]), Number(size[2])] : [320, 640];
for (let i = 0; i < 6; i++) {
  await device.input.tap({ x: Math.round(w / 2), y: Math.round(h * 0.62) });
  await page.waitForTimeout(1100);
}
await device.screenshot({ path: `${OUT}/11-playing.png` });
const state = await page.evaluate(() => window.__stage.state());
console.log('after OS touches:', JSON.stringify(state));
if (!state || state.taps < 1) throw new Error('no OS touch reached the game');

await page.getByTestId('pause').click();
await page.getByRole('button', { name: /Gösteriyi bitir/ }).click();
await page.getByTestId('verified').waitFor({ timeout: 30_000 });
const final = await page.getByTestId('final-score').textContent();
console.log('server-verified final score:', final);
await device.screenshot({ path: `${OUT}/12-results.png` });
await device.close();
console.log('ANDROID WEBVIEW E2E PASS');
