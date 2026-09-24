import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { federation } from '@module-federation/vite';

import pkg from './package.json' with { type: 'json' };
// The one list of what plugin bundles take from Studio; the plugin preset reads it too.
import { SDK, SHARED_LIBRARIES, packageOf } from './packages/plugin-sdk/shared.js';

/**
 * The exact version this build ships: a plugin built against another one is refused by the shared
 * scope instead of silently loading a second copy (ADR-0100).
 */
const exact = (name: keyof typeof pkg.dependencies) => pkg.dependencies[name];

/**
 * What plugin bundles take from the host rather than bringing their own: the libraries that hold
 * state or React context — two copies of any of them breaks hooks, routing, theming or the query
 * cache — and the plugin SDK itself. Everything else a plugin bundles for itself.
 *
 * Sharing a library costs its tree-shaking: a plugin may use any Mantine component, so the host
 * ships all of `@mantine/core` (about 120 KB gzip more on first load). `@mantine/notifications`
 * is not shared for that reason: plugins show notifications through the SDK's `notify`.
 */
const shared = Object.fromEntries([
  ...SHARED_LIBRARIES.map((name) => [
    name,
    { singleton: true, requiredVersion: exact(packageOf(name) as keyof typeof pkg.dependencies) },
  ]),
  [SDK, { singleton: true, requiredVersion: false as const }],
]);

// The Spring Boot app serves the built SPA from classpath:/static and owns
// /api/**. In dev, Vite runs standalone on :5173 and proxies API + SSE to :8080.
export default defineConfig({
  plugins: [
    react(),
    // The host side of Module Federation: plugin bundles are registered at runtime from the
    // manifest (kernel/plugins/boot.ts), never declared here.
    federation({ name: 'artemis_studio', filename: 'remoteEntry.js', shared, dts: false }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': 'http://localhost:8080',
      '/plugin-ui': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // Module Federation's bootstrap awaits the shared scope before the app starts.
    target: 'esnext',
  },
});
