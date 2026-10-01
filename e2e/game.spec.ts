import { execSync } from 'node:child_process';
import { expect, test, type Page } from '@playwright/test';

type DebugState = { clock: number; state: string; score: number; fits: number; misses: number; lives: number; taps: number; msToPerfect: number | null } | null;
const debug = (page: Page) => page.evaluate(() => (window as unknown as { __stage: { state: () => DebugState } }).__stage.state());

/** First launch shows onboarding (stage name); complete it so the home screen appears. */
async function enter(page: Page, name = 'Test Kuklacı'): Promise<void> {
  await page.goto('/?debug=1');
  const onboarding = page.getByTestId('onboard');
  await expect(onboarding.or(page.getByTestId('play'))).toBeVisible();
  if (await onboarding.isVisible()) {
    await page.getByTestId('name-input').fill(name);
    await onboarding.click();
  }
  await expect(page.getByTestId('play')).toBeVisible();
}

/** Tap/click the stage the way a player would: touch on mobile projects, mouse on desktop. */
async function press(page: Page, isMobile: boolean): Promise<void> {
  const box = (await page.locator('#game canvas').boundingBox())!;
  const x = box.x + box.width / 2;
  const y = box.y + box.height * 0.6;
  if (isMobile) await page.touchscreen.tap(x, y);
  else await page.mouse.click(x, y);
}

test('full show: tutorial → real taps → lights out → server-verified results → restart', async ({ page, isMobile }) => {
  await enter(page);
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
  await enter(page);
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

test('pause, resume and end the show from the pause menu', async ({ page }) => {
  await enter(page);
  await page.getByTestId('play').click();
  await page.getByTestId('pause').click();
  const frozen = (await debug(page))!.clock;
  await page.waitForTimeout(600);
  expect((await debug(page))!.clock).toBe(frozen);
  await page.getByRole('button', { name: 'Devam' }).click();
  await expect.poll(async () => (await debug(page))!.clock).toBeGreaterThan(frozen);
  await page.getByTestId('pause').click();
  await page.getByRole('button', { name: /Gösteriyi bitir/ }).click();
  await expect(page.getByTestId('verified')).toBeVisible({ timeout: 15_000 });
});

test('weekly challenge runs on the shared seed and appears on its board', async ({ page }) => {
  await enter(page);
  await page.getByRole('button', { name: /Meydan Okuma/ }).click();
  await page.getByTestId('play-challenge').click();
  await page.getByTestId('pause').click();
  await page.getByRole('button', { name: /Gösteriyi bitir/ }).click();
  await expect(page.getByRole('heading', { name: /Meydan okuma bitti/ })).toBeVisible({ timeout: 15_000 });
});

test('offline: API unreachable → practice mode still playable', async ({ browser }) => {
  const ctx = await browser.newContext();
  await ctx.route('http://localhost:3000/**', (r) => r.abort());
  const page = await ctx.newPage();
  await page.goto('/?debug=1');
  await expect(page.getByText(/Antrenman modu/)).toBeVisible();
  await page.getByTestId('play').click();
  await expect.poll(async () => (await debug(page))?.state).toBe('active');
  await ctx.close();
});

test('live duel: two players, server-decided result', async ({ browser }) => {
  test.skip(test.info().project.name !== 'desktop-mouse', 'one duel run is enough');
  const players = await Promise.all([0, 1].map(async (i) => {
    const ctx = await browser.newContext({ reducedMotion: 'reduce' });
    const page = await ctx.newPage();
    await enter(page, `Düellocu ${i + 1}`);
    return { ctx, page };
  }));
  for (const { page } of players) await page.getByRole('button', { name: /Düello/ }).click();
  for (const { page } of players) await expect(page.getByTestId('opponents')).toBeVisible({ timeout: 20_000 });
  // Player 1 taps a few times with the real mouse; both shows then run out of spotlights.
  for (let i = 0; i < 3; i++) { await press(players[0]!.page, false); await players[0]!.page.waitForTimeout(700); }
  for (const { page } of players) await expect(page.getByTestId('duel-result')).toBeVisible({ timeout: 60_000 });
  await expect(players[0]!.page.getByText('Skorlar sunucu tarafından hesaplandı')).toBeVisible();
  for (const { ctx } of players) await ctx.close();
});

test('admin panel: login, economy stats, pause/resume rewards', async ({ page, request }) => {
  test.skip(test.info().project.name !== 'desktop-mouse', 'admin is a desktop tool');
  const email = `admin${Date.now()}@test.io`;
  const reg = await request.post('http://localhost:3000/auth/register', { data: { email, password: 'admin-pass-123' } });
  expect(reg.ok()).toBe(true);
  execSync(`pnpm --filter @stage/api exec tsx --env-file-if-exists=.env scripts/make-admin.ts ${email}`, { stdio: 'ignore' });
  await page.goto('/admin.html');
  await page.getByPlaceholder('admin e-posta').fill(email);
  await page.getByPlaceholder('şifre').fill('admin-pass-123');
  await page.getByRole('button', { name: 'Giriş' }).click();
  await expect(page.getByTestId('economy')).toBeVisible();
  await page.getByTestId('toggle-rewards').click();
  await expect(page.getByText('DURDURULDU')).toBeVisible();
  await page.getByTestId('toggle-rewards').click();
  await expect(page.getByTestId('economy').getByText('Açık', { exact: true })).toBeVisible();
});
