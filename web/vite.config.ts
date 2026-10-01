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
// /api/**. In dev, Vite runs standalone on :5173 and proxies API + SSE to :8080, or to the Studio
// named by STUDIO_API (an isolated QA stack listens on another port).
const api = process.env.STUDIO_API ?? 'http://localhost:8080';

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
        target: api,
        changeOrigin: true,
      },
      '/actuator': api,
      '/plugin-ui': {
        target: api,
        changeOrigin: true,
      },
    },
  },
  optimizeDeps: {
    // elk.ts takes ELK's worker script as a URL (`?url`). The scanner records that import as a dependency to
    // pre-bundle, and the bundler then looks for a file named with the query. It is an asset, not a module.
    exclude: ['elkjs/lib/elk-worker.min.js?url'],
  },
  build: {
    outDir: 'dist',
    // Module Federation's bootstrap awaits the shared scope before the app starts.
    target: 'esnext',
    // Three chunks are over Vite's 500 kB default, each for a reason that splitting would not remove:
    //  - elk.bundled (about 1.4 MB, the largest, so this limit sits just above it): ELK's single-thread
    //    build, imported only where there is no Worker (tests, Node). A browser lays out in a worker with
    //    the small elk-api, so no visitor downloads it;
    //  - the shared @mantine/core (about 680 kB): every Mantine component, because it is a singleton that
    //    plugin bundles take from the host rather than bringing their own (see `shared` above);
    //  - shiki's wasm (about 620 kB): the syntax highlighter's regular-expression engine, imported when a
    //    code block is first shown.
    // The limit is not a way to hide the warning for anything else. What a first visit downloads is held to
    // a budget of its own: `npm run check:bundle` (scripts/bundle-budget.ts).
    chunkSizeWarningLimit: 1450,
  },
});
