import { useState, type ReactNode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { EventSourceStub } from '../../test/setup.ts';
import { heldOperationsKey } from '../api/request.ts';
import { inboxKeys } from '../inbox/api.ts';
import { useUserStream } from './useUserStream.ts';

describe('useUserStream', () => {
  let qc: QueryClient;

  function Wrapper({ children }: { children: ReactNode }) {
    const [client] = useState(() => qc);
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }

  /** Seeds a query under each key, so an invalidation shows on its state. */
  function seed() {
    qc.setQueryData(inboxKeys.count, { unread: 0, capped: false });
    qc.setQueryData([...heldOperationsKey, 'mine'], []);
  }
  const stale = (queryKey: readonly unknown[]) => qc.getQueryState(queryKey)?.isInvalidated;

  beforeEach(() => {
    vi.useFakeTimers();
    EventSourceStub.reset();
    qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function settle() {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
  }

  it('opens one stream per tab, however many views use it', async () => {
    renderHook(() => [useUserStream(), useUserStream()], { wrapper: Wrapper });
    await settle();
    expect(EventSourceStub.instances).toHaveLength(1);
    expect(EventSourceStub.instances[0].url).toBe('/api/v1/me/stream');
  });

  it('reports live once the stream opens, and closes it when the last user unmounts', async () => {
    const { result, unmount } = renderHook(() => useUserStream(), { wrapper: Wrapper });
    await settle();
    expect(result.current).toBe('live');
    unmount();
    expect(EventSourceStub.instances[0].readyState).toBe(2);
  });

  it('refetches the inbox on an inbox signal, and only the inbox', async () => {
    renderHook(() => useUserStream(), { wrapper: Wrapper });
    await settle();
    seed();
    act(() => EventSourceStub.emit('inbox', ''));
    expect(stale(inboxKeys.count)).toBe(true);
    expect(stale([...heldOperationsKey, 'mine'])).toBe(false);
  });

  it('refetches held operations on a held signal', async () => {
    renderHook(() => useUserStream(), { wrapper: Wrapper });
    await settle();
    seed();
    act(() => EventSourceStub.emit('held', ''));
    expect(stale([...heldOperationsKey, 'mine'])).toBe(true);
    expect(stale(inboxKeys.count)).toBe(false);
  });

  it('refetches everything it covers on resync', async () => {
    renderHook(() => useUserStream(), { wrapper: Wrapper });
    await settle();
    seed();
    act(() => EventSourceStub.emit('resync', ''));
    expect(stale(inboxKeys.count)).toBe(true);
    expect(stale([...heldOperationsKey, 'mine'])).toBe(true);
  });

  it('reconnects after a failure and catches up on what it missed', async () => {
    const { result } = renderHook(() => useUserStream(), { wrapper: Wrapper });
    await settle();
    seed();
    await act(async () => {
      EventSourceStub.instances[0].onerror?.();
    });
    expect(result.current).toBe('reconnecting');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000);
    });
    expect(EventSourceStub.instances).toHaveLength(2);
    expect(result.current).toBe('live');
    expect(stale(inboxKeys.count)).toBe(true);
  });

  it('waits until the tab is used again after a newer tab took its place', async () => {
    const { result } = renderHook(() => useUserStream(), { wrapper: Wrapper });
    await settle();
    seed();
    act(() => EventSourceStub.emit('evicted', ''));
    expect(result.current).toBe('offline');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(EventSourceStub.instances).toHaveLength(1);

    await act(async () => {
      window.dispatchEvent(new Event('focus'));
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(EventSourceStub.instances).toHaveLength(2);
    expect(result.current).toBe('live');
    expect(stale(inboxKeys.count)).toBe(true);
  });
});
