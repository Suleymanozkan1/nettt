import { defineConfig } from 'vitest/config';

// VITE_BASE: sub-path for static hosting (GitHub Pages serves the offline edition under /<repo>/).
const pages = !!process.env.VITE_BASE;

/** Same security headers as production (apps/game/nginx.conf, deploy/caddy/Caddyfile); the CI ZAP scan checks them. */
const securityHeaders = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Content-Security-Policy': "frame-ancestors 'none'",
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Embedder-Policy': 'require-corp',
};

export default defineConfig({
  preview: { headers: securityHeaders },
  base: process.env.VITE_BASE ?? '/',
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 1600,
    rollupOptions: {
      // The admin panel needs the API, so the static (Pages) build leaves it out.
      input: pages ? { main: 'index.html' } : { main: 'index.html', admin: 'admin.html' },
      output: { manualChunks: { phaser: ['phaser'] } },
    },
  },
  test: { environment: 'jsdom' },
});
