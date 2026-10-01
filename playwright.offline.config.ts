import { defineConfig, devices } from '@playwright/test';

const chromium = process.env.PW_CHROMIUM_PATH;
/** PAGES_URL: run against the published GitHub Pages site instead of a local preview. */
const live = process.env.PAGES_URL;

/** Offline edition (standalone APK build): no API or realtime server is started at all. */
export default defineConfig({
  testDir: 'e2e-offline',
  timeout: 120_000,
  retries: 0,
  workers: 1,
  use: {
    baseURL: live ?? 'http://localhost:4174/',
    launchOptions: chromium ? { executablePath: chromium } : {},
    screenshot: 'only-on-failure',
    contextOptions: { reducedMotion: 'reduce' },
    // Only for sandboxes behind a TLS-intercepting proxy (PW_IGNORE_HTTPS_ERRORS=1); never set in CI.
    ignoreHTTPSErrors: process.env.PW_IGNORE_HTTPS_ERRORS === '1',
  },
  projects: [{ name: 'offline-mobile', use: { ...devices['Pixel 7'], browserName: 'chromium' } }],
  webServer: live ? undefined : {
    command: 'VITE_BACKEND=local pnpm --filter @stage/game exec vite build --outDir dist-offline && pnpm --filter @stage/game exec vite preview --outDir dist-offline --port 4174 --strictPort',
    url: 'http://localhost:4174',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
