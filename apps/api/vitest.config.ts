import { defineConfig } from 'vitest/config';

const TEST_DB = process.env.TEST_DATABASE_URL ?? 'postgresql://stage:stage@localhost:5432/stagestack_ci';

export default defineConfig({
  test: {
    globalSetup: ['./test/global-setup.ts'],
    env: { DATABASE_URL: TEST_DB },
    fileParallelism: false,
    testTimeout: 20000,
  },
});
