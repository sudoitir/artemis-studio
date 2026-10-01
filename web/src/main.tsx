import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import { CodeHighlightAdapterProvider, createShikiAdapter } from '@mantine/code-highlight';
import { Notifications } from '@mantine/notifications';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';

import '@fontsource-variable/atkinson-hyperlegible-next/index.css';
import '@fontsource-variable/atkinson-hyperlegible-mono/index.css';
import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/spotlight/styles.css';
import '@mantine/charts/styles.css';
import '@mantine/code-highlight/styles.css';
import '@xyflow/react/dist/style.css';
import './theme.css';

import { installPauseSeam, mountRefetch } from './kernel/api/polling.ts';
import { FEATURES } from './app/features.ts';
import { startServerTimeSync } from './kernel/time/time.ts';
import { FeatureProvider } from './kernel/FeatureProvider.tsx';
import { cssVariablesResolver, theme } from './theme.ts';
import { BootNotice } from './kernel/plugins/BootNotice.tsx';
import { createAppRouter } from './app/router.ts';
import { manifestKey } from './kernel/manifest.ts';
import { boot } from './kernel/plugins/boot.ts';
// A module is only in Module Federation's shared scope when the host bundle imports it; this is
// what hands plugin bundles the running Studio's SDK instead of a copy of their own.
import '@artemis-studio/plugin-sdk';
import { shouldRetry } from './kernel/api/retry.ts';

/**
 * Shiki, loaded by dynamic `import()` so nothing but the adapter itself is in the
 * entry chunk — the app must not pay for a highlighter before anyone opens a code
 * block. Without this provider every `<CodeHighlight language="…">` in the app
 * renders as plain text; no adapter was mounted at all before this.
 *
 * The fine-grained `shiki/core` bundle, not `shiki`'s full one: the full bundle
 * registers every grammar shiki ships as its own lazy chunk (311 files in the dist
 * for five languages we actually use).
 *
 * The theme is Shiki's CSS-variables one, so a token's colour is a semantic `--as-code-*` token
 * chosen per colour scheme in `theme.css` and measured there (non-negotiable 6). Mantine's own
 * highlighter themes are fixed hex values that miss the AA floor in both schemes, and the adapter
 * would apply them per scheme unless one theme is forced.
 */
const CODE_THEME = 'studio';

async function loadShiki() {
  const [{ createHighlighterCore, createCssVariablesTheme }, { createOnigurumaEngine }] = await Promise.all([
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
    themes: [createCssVariablesTheme({ name: CODE_THEME })],
    engine: createOnigurumaEngine(import('shiki/wasm')),
  });
}

const shikiAdapter = createShikiAdapter(loadShiki, { forceColorScheme: CODE_THEME });

/**
 * Pausing is enforced here, once, for every query (ADR-0118): intervals through the
 * focus seam, opening a view through `refetchOnMount`. Pausing intervals and leaving
 * mounts alone would mean an operator who pauses and then navigates has silently
 * unpaused.
 */
installPauseSeam();
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 5_000, refetchOnWindowFocus: false, refetchOnMount: mountRefetch(), retry: shouldRetry },
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
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme="auto">
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
