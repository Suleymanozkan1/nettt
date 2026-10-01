import { expect, test, type Page } from '@playwright/test';

type DebugState = { clock: number; state: string; score: number; fits: number; misses: number; lives: number; taps: number; msToPerfect: number | null } | null;
const debug = (page: Page) => page.evaluate(() => (window as unknown as { __stage: { state: () => DebugState } }).__stage.state());

/** Tap/click the stage the way a player would: touch on mobile projects, mouse on desktop. */
async function press(page: Page, isMobile: boolean): Promise<void> {
  const box = (await page.locator('#game canvas').boundingBox())!;
  const x = box.x + box.width / 2;
  const y = box.y + box.height * 0.6;
  if (isMobile) await page.touchscreen.tap(x, y);
  else await page.mouse.click(x, y);
}

test('full show: tutorial → real taps → lights out → server-verified results → restart', async ({ page, isMobile }) => {
  await page.goto('/?debug=1');
  await expect(page.getByTestId('play')).toBeVisible();
  await page.getByTestId('play').click();
  await expect(page.getByTestId('tutorial')).toBeVisible();
  await expect(page.getByTestId('lives')).toHaveText('💡💡💡');

  // Real taps (touch or mouse), aimed with the read-only debug clock. Headless software rendering adds
  // 80–800 ms of input latency here, so the grade is not asserted — only that every tap is resolved by the
  // game. Scoring precision is covered deterministically by the shared and API replay tests.
  for (let i = 0; i < 4; i++) {
    await expect.poll(async () => { const s = (await debug(page))!; return s.msToPerfect !== null || s.state === 'dead'; }, { timeout: 15_000 }).toBe(true);
    const before = (await debug(page))!;
    if (before.state !== 'active') break;
    await press(page, isMobile);
    await expect.poll(async () => { const s = (await debug(page))!; return s.fits + s.misses > before.fits + before.misses || s.state === 'dead'; }, { timeout: 8000 }).toBe(true);
  }
  await expect(page.getByTestId('tutorial')).toHaveCount(0);
  expect((await debug(page))!.taps).toBeGreaterThanOrEqual(1); // at least one real touch/click was accepted
  const mid = (await debug(page))!;
  if (mid.state === 'active') await expect(page.getByTestId('score')).toHaveText(String(mid.score));
  await page.screenshot({ path: `test-results/play-${isMobile ? 'mobile' : 'desktop'}.png` });

  // Stop tapping: remaining rounds time out and the spotlights go out one by one.
  await expect(page.getByTestId('results')).toBeVisible({ timeout: 40_000 });
  const end = (await debug(page))!;
  expect(end.state).toBe('dead');
  expect(end.lives).toBe(0);
  await page.getByTestId('results').click();
  await expect(page.getByTestId('verified')).toBeVisible({ timeout: 10_000 });
  // The server replayed the recorded inputs and reached exactly the client's score.
  const final = Number(await page.getByTestId('final-score').textContent());
  expect(final).toBe(end.score);
  await page.screenshot({ path: `test-results/results-${isMobile ? 'mobile' : 'desktop'}.png` });

  await page.getByTestId('again').click();
  await expect(page.getByTestId('score')).toHaveText('0');
  await expect.poll(async () => (await debug(page))?.fits).toBe(0);
  await expect(page.getByTestId('lives')).toHaveText('💡💡💡');
});

test('meta screens: daily claim, missions, shop, leaderboard, settings persist', async ({ page }) => {
  await page.goto('/?debug=1');
  await page.getByRole('button', { name: /Günlük/ }).click();
  await page.getByTestId('claim-daily').click();
  await expect(page.getByText(/Yarın tekrar gel/)).toBeVisible();
  await page.getByRole('button', { name: 'Geri', exact: true }).click();
  await expect(page.getByTestId('credits')).toHaveText('50');

  await page.getByRole('button', { name: /Görevler/ }).click();
  await expect(page.locator('.row')).toHaveCount(3);
  await page.getByRole('button', { name: 'Geri', exact: true }).click();

  await page.getByRole('button', { name: /Mağaza/ }).click();
  await expect(page.getByTestId('buy-lamp_neon')).toBeDisabled();
  await page.getByRole('button', { name: 'Geliştirmeler' }).click();
  await expect(page.getByTestId('upgrade-tolerance')).toBeVisible();
  await page.getByRole('button', { name: 'Geri', exact: true }).click();

  await page.getByRole('button', { name: /Sıralama/ }).click();
  await expect(page.getByRole('heading', { name: 'Sıralama' })).toBeVisible();
  await expect(page.getByText(/Senin sıran|Sıralamaya girmek/)).toBeVisible();
  await page.getByRole('button', { name: 'Geri', exact: true }).click();

  await page.getByRole('button', { name: /Ayarlar/ }).click();
  await page.getByTestId('toggle-sound').uncheck();
  await expect.poll(async () => page.evaluate(() => document.querySelector('#toasts')?.textContent ?? '')).not.toContain('Bir şeyler');
  await page.reload();
  await page.getByRole('button', { name: /Ayarlar/ }).click();
  await expect(page.getByTestId('toggle-sound')).not.toBeChecked();
});
