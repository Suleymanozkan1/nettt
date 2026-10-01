// Performance probe: low-end phone emulation (412x915, CPU throttled 4x) against a built game preview.
// Usage: node scripts/perf-probe.mjs [url]   (start `pnpm --filter @stage/game preview` first)
// Note: headless Chromium renders WebGL in software (SwiftShader), so GPU-bound numbers are pessimistic.
import { chromium } from '@playwright/test';

const url = process.argv[2] ?? 'http://localhost:4173/?debug=1';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2.6, isMobile: true, hasTouch: true, reducedMotion: 'reduce' });
const cdp = await page.context().newCDPSession(page);
await cdp.send('Emulation.setCPUThrottlingRate', { rate: Number(process.env.CPU_THROTTLE ?? 4) });

const t0 = Date.now();
await page.goto(url);
await page.waitForSelector('[data-testid="play"]', { timeout: 60_000 });
const startupMs = Date.now() - t0;
const nav = await page.evaluate(() => { const n = performance.getEntriesByType('navigation')[0]; return { domContentLoaded: Math.round(n.domContentLoadedEventEnd), load: Math.round(n.loadEventEnd) }; });
const bytes = await page.evaluate(() => performance.getEntriesByType('resource').reduce((s, r) => s + (r.transferSize || 0), 0));

await page.getByTestId('play').click();
const stats = await page.evaluate(() => new Promise((resolve) => {
  const deltas = []; let last = performance.now(); const end = last + 10_000;
  const longTasks = [];
  try { new PerformanceObserver((l) => l.getEntries().forEach((e) => longTasks.push(e.duration))).observe({ type: 'longtask' }); } catch { /* unsupported */ }
  const tapper = setInterval(() => document.querySelector('#game canvas')?.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })), 900);
  const frame = (now) => {
    deltas.push(now - last); last = now;
    if (now < end) requestAnimationFrame(frame);
    else {
      clearInterval(tapper);
      const s = [...deltas].sort((a, b) => a - b);
      const q = (p) => s[Math.min(s.length - 1, Math.floor(p * s.length))];
      const mem = performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : null;
      resolve({ frames: deltas.length, fps: +(deltas.length / 10).toFixed(1), p50: +q(0.5).toFixed(1), p95: +q(0.95).toFixed(1), p99: +q(0.99).toFixed(1), jank50ms: deltas.filter((d) => d > 50).length, longTasks: longTasks.length, heapMB: mem });
    }
  };
  requestAnimationFrame(frame);
}));
console.log(JSON.stringify({ startupMs, ...nav, transferredKB: Math.round(bytes / 1024), gameplay: stats }, null, 2));
await browser.close();
