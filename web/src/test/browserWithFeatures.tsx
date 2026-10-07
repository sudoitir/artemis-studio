import type { ReactElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { FEATURES } from '../app/features.ts';
import { FeatureProvider } from '../kernel/FeatureProvider.tsx';
import { renderThemed, type Scheme } from './browser.tsx';

/**
 * `ui` in one colour scheme with the application's features installed, for a shell part that lists them (the
 * shortcuts help, the navigation). One Mantine provider only: a second one would set its own scheme on the page.
 * Its own file, so only the tests that need every feature load them: the rest of the browser tests stay small.
 */
export function renderThemedWithFeatures(ui: ReactElement, scheme: Scheme) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return renderThemed(
    <QueryClientProvider client={client}>
      <FeatureProvider features={FEATURES}>{ui}</FeatureProvider>
    </QueryClientProvider>,
    scheme,
  );
}
