import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Standalone from vite.config.ts: the app config carries a dev-server proxy we
// don't want here, and the test run needs the jsdom environment + setup file.
// Same @vitejs/plugin-react transform, so component behaviour matches the build.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.test.{ts,tsx}', 'scripts/**/*.test.ts', 'packages/*/src/**/*.test.ts'],
    // Vitest's 5 s per test is sized for a unit test. These render Mantine, TanStack Router and, for the
    // screens that use them, a virtualised grid or a diagram in jsdom, and a full run puts every core to
    // work at once: a test that takes 2 s alone takes 6 s beside 20 others. A timeout is only the bound on
    // a test that is stuck, so it is set where a slow machine cannot trip it (a stuck one still fails).
    testTimeout: 20_000,
    hookTimeout: 20_000,
    // `npm run test:coverage` (ADR-0128): V8 coverage of the app's own sources, reported as text,
    // HTML, a JSON summary that CI puts in the job summary, and lcov for SonarQube Cloud. No threshold.
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/kernel/api/schema.d.ts', 'src/**/*.d.ts'],
      reporter: ['text-summary', 'html', 'json-summary', 'lcov'],
      reportsDirectory: 'coverage',
    },
  },
});
