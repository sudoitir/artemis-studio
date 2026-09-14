import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';

import { manifestHandler } from '../test/manifest.ts';
import { server } from '../test/setup.ts';
import { CONTRACT, defineFeature } from './feature.ts';
import { FeatureProvider } from './FeatureProvider.tsx';
import { useSlot } from './slots.ts';

function Panel() {
  return null;
}

const features = [
  defineFeature({
    contract: CONTRACT,
    id: 'rr',
    slots: { 'metrics.panels': [{ id: 'rr-second', order: 20, Component: Panel }] },
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'sql',
    slots: { 'metrics.panels': [{ id: 'sql-disabled', order: 5, Component: Panel }] },
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'events',
    slots: { 'metrics.panels': [{ id: 'events-first', order: 10, Component: Panel }] },
  }),
];

describe('useSlot', () => {
  it("orders the contributions and leaves out a disabled feature's", async () => {
    server.use(manifestHandler(['sql']));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>
        <FeatureProvider features={features}>{children}</FeatureProvider>
      </QueryClientProvider>
    );

    const { result } = renderHook(() => useSlot('metrics.panels'), { wrapper });

    await waitFor(() =>
      expect(result.current.map((contribution) => contribution.id)).toEqual(['events-first', 'rr-second']),
    );
  });
});
