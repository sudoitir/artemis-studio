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

import { theme } from './theme.ts';
import { createAppRouter } from './router.tsx';

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

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 5_000, refetchOnWindowFocus: false },
  },
});

const router = createAppRouter(queryClient);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider theme={theme} defaultColorScheme="dark">
      <CodeHighlightAdapterProvider adapter={shikiAdapter}>
        <Notifications position="top-right" />
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </CodeHighlightAdapterProvider>
    </MantineProvider>
  </StrictMode>,
);
