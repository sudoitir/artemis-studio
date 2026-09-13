import type { ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';

import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { useClusterStream } from '../../kernel/stream/useClusterStream.ts';
import { EventSourceStub } from '../../test/setup.ts';
import { clearApplyProgress, useApplyProgress } from './applyProgress.ts';
import { brokerconfigFeature } from './feature.ts';

describe('the config topic handler', () => {
  it('routes an apply-progress frame to the progress store instead of refetching the declaration', async () => {
    // One apply publishes a frame per node per step. Invalidating on each would
    // refetch the declaration dozens of times during a single apply, for a
    // resource whose answer only arrives when the apply's own POST returns.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    const invalidate = vi.spyOn(qc, 'invalidateQueries');
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>
        <FeatureProvider features={[brokerconfigFeature]}>{children}</FeatureProvider>
      </QueryClientProvider>
    );
    const { result } = renderHook(
      () => ({ stream: useClusterStream('c1', ['config']), progress: useApplyProgress() }),
      { wrapper },
    );
    await act(async () => {});

    await act(async () => {
      EventSourceStub.emit('config', {
        kind: 'apply-progress',
        applyId: 7,
        nodeId: 'n-a',
        nodeName: 'broker-1',
        canary: true,
        phase: 'VERIFYING',
        done: 2,
        total: 2,
      });
    });
    expect(result.current.progress).toEqual([
      { kind: 'apply-progress', applyId: 7, nodeId: 'n-a', nodeName: 'broker-1', canary: true, phase: 'VERIFYING', done: 2, total: 2 },
    ]);
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: ['clusters', 'c1', 'config'] });

    // The ordinary signal still means "the declaration moved, go and read it".
    await act(async () => {
      EventSourceStub.emit('config', { topic: 'config', clusterId: 'c1' });
    });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['clusters', 'c1', 'config'] });
    act(() => clearApplyProgress());
  });
});
