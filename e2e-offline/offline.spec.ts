import { expect, test, type Page } from '@playwright/test';

type DebugState = { state: string; score: number; fits: number; misses: number; taps: number; msToPerfect: number | null } | null;
const debug = (page: Page) => page.evaluate(() => (window as unknown as { __stage: { state: () => DebugState } }).__stage.state());

test('offline edition: no server at all, full progression stored on the device', async ({ page }) => {
  const remote: string[] = [];
  page.on('request', (r) => { if (!r.url().startsWith('http://localhost:4174') && !r.url().startsWith('data:') && !r.url().startsWith('blob:')) remote.push(r.url()); });

  await page.goto('/?debug=1');
  await page.getByTestId('name-input').fill('Yolcu');
  await page.getByTestId('onboard').click();
  await expect(page.getByTestId('offline-edition')).toBeVisible();
  await expect(page.getByRole('button', { name: /Düello/ })).toHaveCount(0);

  // Safe areas: insets injected by the Android shell (--native-*) drive the layout padding.
  const insets = () => page.evaluate(() => (window as unknown as { __stage: { insets: () => string[] } }).__stage.insets());
  expect(await insets()).toEqual(['0px', '0px', '0px', '0px']);
  await page.evaluate(() => document.documentElement.style.setProperty('--native-sat', '31px'));
  expect((await insets())[0]).toBe('31px');
  await page.evaluate(() => document.documentElement.style.removeProperty('--native-sat'));

  // Daily reward pays immediately from local state.
  await page.getByRole('button', { name: /Günlük/ }).click();
  await page.getByTestId('claim-daily').click();
  await page.getByRole('button', { name: 'Geri', exact: true }).click();
  await expect(page.getByTestId('credits')).toHaveText('50');

  // A real show with touches; results are computed by replaying the inputs with the shared rules.
  await page.getByTestId('play').click();
  for (let i = 0; i < 3; i++) {
    await expect.poll(async () => { const s = (await debug(page))!; return s.msToPerfect !== null || s.state === 'dead'; }, { timeout: 15_000 }).toBe(true);
    if ((await debug(page))!.state !== 'active') break;
    const box = (await page.locator('#game canvas').boundingBox())!;
    await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height * 0.6);
    await page.waitForTimeout(600);
  }
  await expect(page.getByTestId('results')).toBeVisible({ timeout: 40_000 });
  const end = (await debug(page))!;
  await page.getByTestId('results').click();
  await expect(page.getByTestId('verified')).toBeVisible();
  expect(Number(await page.getByTestId('final-score').textContent())).toBe(end.score);
  await page.screenshot({ path: 'test-results/offline-results.png' });
  await page.getByRole('button', { name: 'Ana sayfa' }).click();

  // Personal records board + progress survive an app restart.
  await page.getByRole('button', { name: /Sıralama/ }).click();
  await expect(page.getByRole('heading', { name: 'Rekorlarım' })).toBeVisible();
  await page.getByRole('button', { name: 'Geri', exact: true }).click();
  const credits = await page.getByTestId('credits').textContent();
  await page.reload();
  await expect(page.getByTestId('play')).toBeVisible();
  await expect(page.getByTestId('credits')).toHaveText(credits!);
  await page.getByRole('button', { name: /Günlük/ }).click();
  await expect(page.getByText(/Yarın tekrar gel/)).toBeVisible();

  expect(remote).toEqual([]); // nothing left the device
});
