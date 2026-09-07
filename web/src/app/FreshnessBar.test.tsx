import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MantineProvider } from '@mantine/core';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { theme } from '../theme.ts';
import {
  isPollingPaused,
  markPendingChange,
  mountRefetch,
  poll,
  setPollingPaused,
} from '../api/polling.ts';
import { FreshnessBar } from './FreshnessBar.tsx';

/**
 * A screen with one observed query, which is what the bar reports on. The fetch
 * count is the assertion surface: refresh must refetch it, pause must not.
 */
function Screen({ fetcher, intervalMs }: { fetcher: () => Promise<string>; intervalMs?: number }) {
  const q = useQuery({
    queryKey: ['probe'],
    queryFn: fetcher,
    refetchInterval: intervalMs ? poll(intervalMs) : undefined,
  });
  return <div>{q.data ?? 'pending'}</div>;
}

/** `refetchOnMount` through the pause seam, exactly as `main.tsx` wires it. */
function makeClient() {
  return new QueryClient({
    defaultOptions: {
      // `gcTime` is finite rather than 0 so an unmounted query survives long
      // enough to be re-observed, which is what navigating away and back does.
      queries: { retry: false, gcTime: 60_000, staleTime: 0, refetchOnMount: mountRefetch() },
    },
  });
}

function harness(ui: ReactNode, qc: QueryClient = makeClient()) {
  return render(
    <MantineProvider theme={theme} defaultColorScheme="dark">
      <QueryClientProvider client={qc}>
        <FreshnessBar />
        {ui}
      </QueryClientProvider>
    </MantineProvider>,
  );
}

afterEach(() => setPollingPaused(false));

describe('FreshnessBar', () => {
  it('reports the age of a screen that never refetches', async () => {
    // Seventeen hooks poll on no interval at all. Before this the page looked
    // identical whether its data arrived a second or an hour ago.
    harness(<Screen fetcher={() => Promise.resolve('ok')} />);

    await screen.findByText('ok');
    const stamp = await screen.findByText(/updated .* ago/);
    expect(stamp.closest('time')).not.toBeNull();
    expect(stamp.closest('time')).toHaveAttribute('dateTime');
  });

  it('refreshes the current screen on demand', async () => {
    const user = userEvent.setup();
    const fetcher = vi.fn().mockResolvedValue('ok');
    harness(<Screen fetcher={fetcher} />);
    await screen.findByText('ok');
    expect(fetcher).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: 'Refresh data' }));

    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  });

  it('does not look busy while the background poll is fetching', async () => {
    // The control used to bind to the cache's `isFetching`, which is true on every
    // interval tick — so on a 5s poll it was a spinner every five seconds and the
    // acknowledgement meant nothing.
    let release: (v: string) => void = () => {};
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce('ok')
      .mockImplementationOnce(() => new Promise<string>((r) => (release = r)));

    harness(<Screen fetcher={fetcher} intervalMs={50} />);
    await screen.findByText('ok');

    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    const button = screen.getByRole('button', { name: 'Refresh data' });
    expect(button).not.toHaveAttribute('data-loading');
    // The label's job is the opposite: it reports all fetching.
    expect(screen.getByText(/Polling|Live/)).toBeInTheDocument();
    release('ok');
  });

  it('absorbs repeated activation instead of restarting the refetch', async () => {
    const user = userEvent.setup();
    let release: (v: string) => void = () => {};
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce('ok')
      .mockImplementation(() => new Promise<string>((r) => (release = r)));

    harness(<Screen fetcher={fetcher} />);
    await screen.findByText('ok');

    const button = screen.getByRole('button', { name: 'Refresh data' });
    await user.click(button);
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
    expect(button).toHaveAttribute('data-loading', 'true');

    // Three more activations while the first is still in flight.
    await user.click(button);
    await user.click(button);
    await user.click(button);
    expect(fetcher).toHaveBeenCalledTimes(2);

    release('ok');
    await waitFor(() => expect(button).not.toHaveAttribute('data-loading'));
  });

  it('renders the pause control differently when paused, by more than colour', async () => {
    const user = userEvent.setup();
    harness(<Screen fetcher={() => Promise.resolve('ok')} />);
    await screen.findByText('ok');

    const pause = screen.getByRole('button', { name: 'Pause auto-refresh' });
    expect(pause).toHaveAttribute('aria-pressed', 'false');
    const before = pause.className;

    await user.click(pause);

    const resume = await screen.findByRole('button', { name: 'Resume auto-refresh' });
    expect(resume).toHaveAttribute('aria-pressed', 'true');
    expect(resume.className).not.toBe(before);
  });

  it('does not refetch a held query when a paused screen is opened', async () => {
    const qc = makeClient();
    const fetcher = vi.fn().mockResolvedValue('ok');
    const view = harness(<Screen fetcher={fetcher} />, qc);
    await screen.findByText('ok');
    expect(fetcher).toHaveBeenCalledTimes(1);

    act(() => setPollingPaused(true));
    view.unmount();

    // The same query key observed again, against the same cache — which is what
    // navigating away and back does. Before this, `refetchOnMount` refetched every
    // stale query and pause covered only the intervals.
    harness(<Screen fetcher={fetcher} />, qc);
    expect(await screen.findByText('ok')).toBeInTheDocument();
    await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(1));
  });

  it('pausing says so, and stops the interval', async () => {
    vi.useFakeTimers();
    try {
      const fetcher = vi.fn().mockResolvedValue('ok');
      harness(<Screen fetcher={fetcher} intervalMs={1_000} />);
      await vi.advanceTimersByTimeAsync(0);
      expect(fetcher).toHaveBeenCalledTimes(1);

      // Toggled through the signal rather than the button: the button's own click
      // is covered by the refresh case, and fake timers make pointer events fiddly.
      act(() => setPollingPaused(true));
      expect(isPollingPaused()).toBe(true);
      expect(screen.getByText(/Paused/)).toBeInTheDocument();

      // One interval may already be in flight when the flag flips; what must not
      // happen is a new one after that.
      await vi.advanceTimersByTimeAsync(2_000);
      const settled = fetcher.mock.calls.length;
      await vi.advanceTimersByTimeAsync(10_000);
      expect(fetcher.mock.calls.length).toBe(settled);
    } finally {
      vi.useRealTimers();
    }
  });

  it('resuming starts the interval again', async () => {
    vi.useFakeTimers();
    try {
      const fetcher = vi.fn().mockResolvedValue('ok');
      harness(<Screen fetcher={fetcher} intervalMs={1_000} />);
      await vi.advanceTimersByTimeAsync(0);

      act(() => setPollingPaused(true));
      await vi.advanceTimersByTimeAsync(5_000);
      const paused = fetcher.mock.calls.length;

      // A live signal arriving while paused is a backlog the bar reports; resuming
      // is about to fetch it, so leaving the report standing would call a live
      // screen stale.
      act(() => markPendingChange());
      expect(screen.getByText(/new data available/)).toBeInTheDocument();

      act(() => setPollingPaused(false));
      await vi.advanceTimersByTimeAsync(3_000);

      expect(fetcher.mock.calls.length).toBeGreaterThan(paused);
      expect(screen.queryByText(/new data available/)).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('still fetches a query that has never resolved, even while paused', async () => {
    // Suspending a first fetch hands the operator an empty screen. They paused a
    // screen showing data to stop it moving, not to stop data existing.
    act(() => setPollingPaused(true));
    const fetcher = vi.fn().mockResolvedValue('ok');
    harness(<Screen fetcher={fetcher} />);

    expect(await screen.findByText('ok')).toBeInTheDocument();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('announces the state politely without narrating every tick', async () => {
    harness(<Screen fetcher={() => Promise.resolve('ok')} />);
    await screen.findByText('ok');

    const region = screen.getByRole('status');
    // The elapsed label lives outside the live region on purpose: it changes every
    // second, and announcing that would talk over whatever the operator is doing.
    expect(region).not.toHaveTextContent(/updated/);
  });
});
