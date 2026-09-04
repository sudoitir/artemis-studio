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
  },
});
