import { useState, type ReactElement, type ReactNode } from 'react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderOptions } from '@testing-library/react';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';

import { FEATURES } from '../app/features.ts';
import { createAppRouter } from '../app/router.ts';
import type { StudioFeature } from '../kernel/feature.ts';
import { ActionHostProvider } from '../kernel/actions/ActionHost.tsx';
import { FeatureProvider } from '../kernel/FeatureProvider.tsx';
import { theme } from '../theme.ts';

/** A fresh QueryClient per render, retries off so a mocked error surfaces at once. */
function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
}

function Providers({ children, client }: { children: ReactNode; client?: QueryClient }) {
  const [own] = useState(makeClient);
  return (
    <MantineProvider theme={theme} defaultColorScheme="dark">
      <QueryClientProvider client={client ?? own}>
        <FeatureProvider features={FEATURES}>
          <ActionHostProvider>{children}</ActionHostProvider>
        </FeatureProvider>
      </QueryClientProvider>
    </MantineProvider>
  );
}

/** Renders `ui` under the providers; `client` is the QueryClient it runs against, for a test that inspects the cache. */
export function renderWithProviders(ui: ReactElement, options?: RenderOptions) {
  const client = makeClient();
  const result = render(ui, {
    wrapper: ({ children }) => <Providers client={client}>{children}</Providers>,
    ...options,
  });
  return { ...result, client };
}

/**
 * The whole application at `path`: the composed route tree of `features`, with the shell, over an
 * in-memory history. For what only the real router shows — which view an address reaches, a
 * redirect, a disabled feature's deep link — where a mocked router would assert the mock.
 */
export function renderAppAt(path: string, features: StudioFeature[] = FEATURES) {
  const router = createAppRouter(makeClient(), features, createMemoryHistory({ initialEntries: [path] }));
  const result = render(<RouterProvider router={router} />, { wrapper: Providers });
  return { ...result, router };
}
