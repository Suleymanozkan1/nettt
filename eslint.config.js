import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import globals from 'globals';

export default tseslint.config(
  { ignores: ['**/dist/**', '**/dist-offline/**', '**/dist-pages/**', '**/node_modules/**', 'apps/game/android/**', 'apps/game/ios/**', 'docs/**', 'test-results/**', 'playwright-report/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      'no-console': ['error', { allow: ['warn', 'error'] }],
    },
  },
  // CLI scripts print their report to stdout.
  { files: ['scripts/**', '**/scripts/**'], rules: { 'no-console': 'off' } },
);
