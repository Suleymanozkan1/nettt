import { defineConfig } from 'vitest/config';

export default defineConfig({
  build: {
    target: 'es2022',
    chunkSizeWarningLimit: 1600,
    rollupOptions: {
      input: { main: 'index.html', admin: 'admin.html' },
      output: { manualChunks: { phaser: ['phaser'] } },
    },
  },
  test: { environment: 'jsdom' },
});
