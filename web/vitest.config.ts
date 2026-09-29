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
    include: ['src/**/*.test.{ts,tsx}'],
    // `npm run test:coverage` (ADR-0128): V8 coverage of the app's own sources, reported as text,
    // HTML and a JSON summary that CI puts in the job summary. Informational, no threshold.
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/kernel/api/schema.d.ts', 'src/**/*.d.ts'],
      reporter: ['text-summary', 'html', 'json-summary'],
      reportsDirectory: 'coverage',
    },
  },
});
