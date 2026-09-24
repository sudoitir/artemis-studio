import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import { CodeHighlightAdapterProvider, createShikiAdapter } from '@mantine/code-highlight';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';

import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/spotlight/styles.css';
import '@mantine/charts/styles.css';
import '@mantine/code-highlight/styles.css';
import '@xyflow/react/dist/style.css';
import './theme.css';

import { mountRefetch } from './kernel/api/polling.ts';
import { FEATURES } from './app/features.ts';
import { startServerTimeSync } from './kernel/time/time.ts';
import { FeatureProvider } from './kernel/FeatureProvider.tsx';
import { theme } from './theme.ts';
import { BootNotice } from './kernel/plugins/BootNotice.tsx';
import { createAppRouter } from './app/router.ts';
import { manifestKey } from './kernel/manifest.ts';
import { boot } from './kernel/plugins/boot.ts';
// A module is only in Module Federation's shared scope when the host bundle imports it; this is
// what hands plugin bundles the running Studio's SDK instead of a copy of their own.
import '@artemis-studio/plugin-sdk';

/**
 * Shiki, loaded by dynamic `import()` so nothing but the adapter itself is in the
 * entry chunk — the app must not pay for a highlighter before anyone opens a code
 * block. Without this provider every `<CodeHighlight language="…">` in the app
 * renders as plain text; no adapter was mounted at all before this.
 *
 * The fine-grained `shiki/core` bundle, not `shiki`'s full one: the full bundle
 * registers every grammar shiki ships as its own lazy chunk (311 files in the dist
 * for five languages we actually use). `themes: []` is correct — Mantine's adapter
 * passes its own light/dark theme per call.
 */
async function loadShiki() {
  const [{ createHighlighterCore }, { createOnigurumaEngine }] = await Promise.all([
    import('shiki/core'),
    import('shiki/engine/oniguruma'),
  ]);
  return createHighlighterCore({
    langs: [
      import('@shikijs/langs/json'),
      import('@shikijs/langs/xml'),
      import('@shikijs/langs/yaml'),
      import('@shikijs/langs/sql'),
      import('@shikijs/langs/properties'),
    ],
    themes: [],
    engine: createOnigurumaEngine(import('shiki/wasm')),
  });
}

const shikiAdapter = createShikiAdapter(loadShiki);

/**
 * `refetchOnMount` goes through the pause seam (`api/polling.ts`), not a literal:
 * pausing the intervals and leaving mounts alone means an operator who pauses and
 * then navigates has silently unpaused (ADR-0052, ADR-0055).
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 5_000, refetchOnWindowFocus: false, refetchOnMount: mountRefetch() },
  },
});

// Learn Studio's clock before anything renders a duration. Started outside React
// so it survives StrictMode's double-mount and is not tied to any one route
// (`app/time.ts`); it never rejects, so nothing downstream has to handle it.
startServerTimeSync();

// Plugins are loaded before the router exists, because their routes are part of it (ADR-0100).
// Nothing here can keep Studio's own screens from loading: every step is bounded, and a failure
// leaves the plugin out with its reason (kernel/plugins/boot.ts).
const started = await boot();
if (started.manifest) queryClient.setQueryData(manifestKey, started.manifest);
const features = [...FEATURES, ...started.plugins];
const router = createAppRouter(queryClient, features);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider theme={theme} defaultColorScheme="dark">
      <CodeHighlightAdapterProvider adapter={shikiAdapter}>
        <Notifications position="top-right" />
        <BootNotice />
        <QueryClientProvider client={queryClient}>
          <FeatureProvider features={features}>
            <RouterProvider router={router} />
          </FeatureProvider>
        </QueryClientProvider>
      </CodeHighlightAdapterProvider>
    </MantineProvider>
  </StrictMode>,
);
