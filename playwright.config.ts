import { defineConfig, devices } from '@playwright/test';

const chromium = process.env.PW_CHROMIUM_PATH;

export default defineConfig({
  testDir: 'e2e',
  timeout: 120_000,
  retries: 0,
  workers: 1,
  use: {
    baseURL: 'http://localhost:4173',
    launchOptions: chromium ? { executablePath: chromium } : {},
    screenshot: 'only-on-failure',
    // Honour prefers-reduced-motion so pulsing buttons are stable to click (the CSS disables animations).
    contextOptions: { reducedMotion: 'reduce' },
  },
  projects: [
    { name: 'mobile-touch', use: { ...devices['Pixel 7'], browserName: 'chromium' } },
    { name: 'desktop-mouse', use: { viewport: { width: 1280, height: 800 }, browserName: 'chromium' } },
  ],
  webServer: [
    {
      command: 'pnpm --filter @stage/api start',
      // All e2e browsers share 127.0.0.1; lift the per-IP guest cap for the test run only.
      env: { GUEST_ACCOUNTS_PER_IP_PER_DAY: '100000', AUTH_RATE_LIMIT_PER_MIN: '1000' },
      url: 'http://localhost:3000/health',
      reuseExistingServer: false,
      timeout: 60_000,
    },
    {
      command: 'pnpm --filter @stage/api realtime',
      url: 'http://localhost:2567/health',
      reuseExistingServer: false,
      timeout: 60_000,
    },
    {
      command: 'pnpm --filter @stage/game build && pnpm --filter @stage/game preview',
      url: 'http://localhost:4173',
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ],
});
