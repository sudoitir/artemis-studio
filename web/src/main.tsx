import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import { CodeHighlightAdapterProvider } from '@mantine/code-highlight';
import { Notifications } from '@mantine/notifications';
import { MutationCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
import { OperationHeldError, onLoginPage } from './kernel/api/request.ts';
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
import { shikiAdapter } from './ui/codeHighlightAdapter.ts';
import { installDefaultPolicy } from './ui/trustedTypes.ts';
import { setInAppNavigate } from './ui/inAppNavigation.ts';

// Before anything renders: Mantine writes its CSS through `innerHTML`, which Trusted Types refuses
// without it (`ui/trustedTypes.ts`, ADR-0168).
installDefaultPolicy();

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
  // A held operation skips its mutation's success path, which is where a view refreshes. Refresh every view on
  // screen instead, so the resource shows that a change waits for approval. Held operations are rare.
  mutationCache: new MutationCache({
    onError: (error) => {
      if (error instanceof OperationHeldError) void queryClient.invalidateQueries();
    },
  }),
});

// Learn Studio's clock before anything renders a duration. Started outside React
// so it survives StrictMode's double-mount and is not tied to any one route
// (`app/time.ts`); it never rejects, so nothing downstream has to handle it. The sign-in page
// has no session to ask with: signing in reloads the page, which starts the clock signed in.
if (!onLoginPage()) startServerTimeSync();

// Plugins are loaded before the router exists, because their routes are part of it (ADR-0100).
// Nothing here can keep Studio's own screens from loading: every step is bounded, and a failure
// leaves the plugin out with its reason (kernel/plugins/boot.ts).
const started = await boot();
if (started.manifest) queryClient.setQueryData(manifestKey, started.manifest);
const features = [...FEATURES, ...started.plugins];
const router = createAppRouter(queryClient, features);
// A toast's link (such as a held operation's "View request") moves inside the app, not by a page load.
setInAppNavigate((to) => router.history.push(to));

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme="auto">
      <CodeHighlightAdapterProvider adapter={shikiAdapter}>
        {/* Under the header (theme.css), three at most: a fourth waits its turn rather than pushing a
            column of them down the page. */}
        <Notifications position="top-right" limit={3} transitionDuration={theme.other?.motion.slow} />
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
