import { defineConfig } from 'vitest/config';

// VITE_BASE: sub-path for static hosting (GitHub Pages serves the offline edition under /<repo>/).
const pages = !!process.env.VITE_BASE;

export default defineConfig({
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
