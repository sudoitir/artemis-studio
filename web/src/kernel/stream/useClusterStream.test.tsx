import { useState, type ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { manifestHandler } from '../../test/manifest.ts';
import { EventSourceStub, server } from '../../test/setup.ts';
import { CONTRACT, defineFeature, type TopicHandler } from '../feature.ts';
import { FeatureProvider } from '../FeatureProvider.tsx';
import { useClusterStream } from './useClusterStream.ts';

/** Fail the newest stub the way an `EventSource` does: error, then closed. */
async function failCurrent() {
  const es = EventSourceStub.instances.at(-1);
  await act(async () => {
    es?.onerror?.();
  });
}

/** Let the jittered backoff timer fire, whatever delay it picked (capped at 30s). */
async function runBackoff() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(31_000);
  });
}

describe('useClusterStream', () => {
  function Wrapper({ children }: { children: ReactNode }) {
    const [qc] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } }));
    return (
      <QueryClientProvider client={qc}>
        <FeatureProvider features={[]}>{children}</FeatureProvider>
      </QueryClientProvider>
    );
  }

  beforeEach(() => {
    vi.useFakeTimers();
    EventSourceStub.reset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps reconnecting past the failure count that used to make it give up', async () => {
    // It stopped at two consecutive failures and handed over to polling — which on
    // the screens that never poll meant handing over to nothing (ADR-0052).
    const { result } = renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    for (let attempt = 0; attempt < 4; attempt += 1) {
      const before = EventSourceStub.instances.length;
      await failCurrent();
      // The state is reported rather than swallowed, which is what the header reads.
      expect(result.current).toBe('reconnecting');
      await runBackoff();
      expect(EventSourceStub.instances.length).toBeGreaterThan(before);
    }
  });

  it('reports live again once the server comes back, with no reload', async () => {
    const { result } = renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current).toBe('live');

    await failCurrent();
    expect(result.current).toBe('reconnecting');

    await runBackoff();
    expect(result.current).toBe('live');
  });

  it('reconnects when the stream falls silent, even with no error', async () => {
    // An intermediary that drops the connection without a clean close fires no
    // error at all. Missing the keep-alive is the only way to notice.
    renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(EventSourceStub.instances).toHaveLength(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(46_000);
    });
    await runBackoff();

    expect(EventSourceStub.instances.length).toBeGreaterThan(1);
  });

  it('a keep-alive frame counts as life, so a busy-but-quiet stream is left alone', async () => {
    renderHook(() => useClusterStream('c1', ['topology']), { wrapper: Wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });

    // Beat well inside the silence window, repeatedly, past where it would expire.
    for (let beat = 0; beat < 5; beat += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(15_000);
        EventSourceStub.emit('ping', Date.now());
      });
    }

    expect(EventSourceStub.instances).toHaveLength(1);
  });
});

describe('useClusterStream topic dispatch', () => {
  it("hands each frame to the handler its feature contributes, and none to a disabled feature's", async () => {
    server.use(manifestHandler(['rr']));
    const queues = vi.fn<TopicHandler>();
    const rr = vi.fn<TopicHandler>();
    const features = [
      defineFeature({ contract: CONTRACT, id: 'queues', streamTopics: { queues } }),
      defineFeature({ contract: CONTRACT, id: 'rr', streamTopics: { rr } }),
    ];
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>
        <FeatureProvider features={features}>{children}</FeatureProvider>
      </QueryClientProvider>
    );
    renderHook(() => useClusterStream('c1', ['queues', 'rr']), { wrapper });
    // The manifest disabling rr has to arrive before the frames do.
    await vi.waitFor(() => expect(qc.getQueryData(['manifest'])).toBeDefined());

    await act(async () => {
      EventSourceStub.emit('queues', { topic: 'queues', clusterId: 'c1' });
      EventSourceStub.emit('rr', { topic: 'rr', clusterId: 'c1' });
    });

    expect(queues).toHaveBeenCalledWith(expect.objectContaining({ clusterId: 'c1' }));
    expect(rr).not.toHaveBeenCalled();
  });
});
