import { defineConfig, type Plugin } from 'vitest/config';
import { loadEnv } from 'vite';

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
  'Cross-Origin-Resource-Policy': 'same-origin',
};

/**
 * CSP connect-src is built from the configured endpoints, so each build allows exactly the hosts it talks to
 * (no dev or emulator addresses in production, nothing at all besides 'self' in the offline edition).
 */
function connectSrc(env: Record<string, string>): string {
  if (env.VITE_BACKEND === 'local') return "'self'";
  const api = new URL(env.VITE_API_URL || 'http://localhost:3000');
  const rt = new URL(env.VITE_REALTIME_URL || 'ws://localhost:2567');
  const rtHttp = new URL(rt.href.replace(/^ws/, 'http')); // Colyseus matchmaking is HTTP on the same host
  const extra = (env.VITE_PIN_SELFTEST ?? '').split(',').filter(Boolean).map((u) => new URL(u).origin);
  return [...new Set(["'self'", api.origin, rt.origin, rtHttp.origin, ...extra])].join(' ');
}

const cspPlugin = (env: Record<string, string>): Plugin => ({
  name: 'csp-connect-src',
  transformIndexHtml: (html) => html.replace('%CONNECT_SRC%', connectSrc(env)),
});

export default defineConfig(({ mode }) => ({
  plugins: [cspPlugin({ ...loadEnv(mode, process.cwd(), 'VITE_'), ...process.env } as Record<string, string>)],
  preview: { headers: securityHeaders },
  base: process.env.VITE_BASE ?? '/',
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 1600,
    rollupOptions: {
      // The admin panel needs the API, so the static (Pages) build leaves it out.
      input: (pages ? { main: 'index.html' } : { main: 'index.html', admin: 'admin.html' }) as Record<string, string>,
      output: { manualChunks: { phaser: ['phaser'] } },
    },
  },
  test: { environment: 'jsdom' },
}));
