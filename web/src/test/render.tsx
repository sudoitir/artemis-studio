import type { ReactElement, ReactNode } from 'react';
import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderOptions } from '@testing-library/react';

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
      <QueryClientProvider client={makeClient()}>{children}</QueryClientProvider>
    </MantineProvider>
  );
}

export function renderWithProviders(ui: ReactElement, options?: RenderOptions) {
  return render(ui, { wrapper: Providers, ...options });
}
