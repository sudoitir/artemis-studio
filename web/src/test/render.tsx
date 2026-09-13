import type { ReactElement, ReactNode } from 'react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderOptions } from '@testing-library/react';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';

import { FEATURES } from '../app/features.ts';
import { createAppRouter } from '../app/router.ts';
import type { StudioFeature } from '../kernel/feature.ts';
import { FeatureProvider } from '../kernel/FeatureProvider.tsx';
import { theme } from '../theme.ts';

/** A fresh QueryClient per render, retries off so a mocked error surfaces at once. */
function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
}

function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider theme={theme} defaultColorScheme="dark">
      <QueryClientProvider client={makeClient()}>
        <FeatureProvider features={FEATURES}>{children}</FeatureProvider>
      </QueryClientProvider>
    </MantineProvider>
  );
}

export function renderWithProviders(ui: ReactElement, options?: RenderOptions) {
  return render(ui, { wrapper: Providers, ...options });
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
