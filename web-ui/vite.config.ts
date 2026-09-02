import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov', 'json-summary'],
      // Type-only and entrypoint files carry no testable behaviour.
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test-setup.ts', 'src/vite-env.d.ts', 'src/main.tsx'],
      // A ratchet, not a target. Coverage was reported and enforced nowhere, so it could fall to
      // any level between two green builds -- the one thing a coverage report exists to prevent.
      // These sit just under what the suite actually achieves, so the gate blocks regression
      // without inventing a standard the code has not already met. Raise them as the real figure
      // rises; never lower them to turn a red build green.
      thresholds: {
        statements: 87,
        branches: 79,
        functions: 76,
        lines: 88,
      },
    },
  },
});
