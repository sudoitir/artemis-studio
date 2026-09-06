import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MantineProvider } from '@mantine/core';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { theme } from '../theme.ts';
import { isPollingPaused, poll, setPollingPaused } from '../api/polling.ts';
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

function harness(ui: ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
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

      act(() => setPollingPaused(false));
      await vi.advanceTimersByTimeAsync(3_000);

      expect(fetcher.mock.calls.length).toBeGreaterThan(paused);
    } finally {
      vi.useRealTimers();
    }
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
