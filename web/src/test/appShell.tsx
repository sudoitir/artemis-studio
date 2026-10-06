import type { QueryClient } from '@tanstack/react-query';
import { QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, RouterProvider } from '@tanstack/react-router';
import { render } from '@testing-library/react';

import { FEATURES } from '../app/features.ts';
import { createAppRouter } from '../app/router.ts';
import type { StudioFeature } from '../kernel/feature.ts';
import { FeatureProvider } from '../kernel/FeatureProvider.tsx';
import { Themed, type Scheme } from './browser.tsx';

/**
 * The whole application at `path` in the browser window, as `main.tsx` mounts it: the real shell (header,
 * navigation, main area), the real route tree of Studio's features and any `plugins` and the real theme, over an in-memory history.
 * For what only the shell's own layout decides, such as whether a page fits its window or what the header
 * covers. Nothing is fetched: the test seeds `client`, and runs with the network off.
 */
export function renderApp(path: string, client: QueryClient, scheme: Scheme, plugins: StudioFeature[] = []) {
  const features = [...FEATURES, ...plugins];
  const router = createAppRouter(client, features, createMemoryHistory({ initialEntries: [path] }));
  return render(
    <Themed scheme={scheme}>
      <QueryClientProvider client={client}>
        <FeatureProvider features={features}>
          <RouterProvider router={router} />
        </FeatureProvider>
      </QueryClientProvider>
    </Themed>,
  );
}
