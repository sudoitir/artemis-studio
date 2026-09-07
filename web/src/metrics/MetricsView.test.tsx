import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

let currentSearch: Record<string, unknown> = {};
const navigateSpy = vi.fn();

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => currentSearch,
  useNavigate: () => navigateSpy,
}));

const { MetricsView } = await import('./MetricsView.tsx');

function series(metric: string, kind: string, points: { ts: string; value: number; peak?: number }[]) {
  return { metric, kind, unit: kind === 'GAUGE' ? 'count' : 'msg/s', points };
}

describe('MetricsView', () => {
  it('reports a failed read rather than an absence of samples', async () => {
    currentSearch = { range: '1h' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', () =>
        HttpResponse.json({ title: 'Upstream unavailable', detail: 'the broker did not answer' }, { status: 502 }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    renderWithProviders(<MetricsView />);

    expect(await screen.findAllByText(/did not answer/)).not.toHaveLength(0);
    expect(screen.queryByText(/No depth samples/)).not.toBeInTheDocument();
  });

  it('states the current values, and says so when one was never sampled', async () => {
    currentSearch = { range: '1h' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', () =>
        HttpResponse.json({
          from: '2026-09-04T09:00:00.000Z',
          to: '2026-09-04T10:00:00.000Z',
          step: 'PT1M',
          truncated: false,
          series: [
            series('messageCount', 'GAUGE', [
              { ts: '2026-09-04T09:00:00.000Z', value: 10 },
              { ts: '2026-09-04T09:01:00.000Z', value: 4200 },
            ]),
            // consumerCount is absent entirely: not zero, not sampled.
          ],
        }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    renderWithProviders(<MetricsView />);

    // 4,200 messages, compacted — the digits do not fit an axis tick or a tile.
    // Both the tile and the table state it, which is the point of having both.
    expect(await screen.findAllByText('4.2K')).not.toHaveLength(0);
    // An unsampled metric is never rendered as zero.
    expect(await screen.findAllByText('Not sampled')).not.toHaveLength(0);
  });

  it('advances a relative window, and leaves an absolute one alone', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const windows: Array<{ from: string; to: string }> = [];
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', ({ request }) => {
        const url = new URL(request.url);
        windows.push({ from: url.searchParams.get('from')!, to: url.searchParams.get('to')! });
        return HttpResponse.json({
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          step: 'PT1M',
          truncated: false,
          series: [],
        });
      }),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    try {
      currentSearch = { range: '1h' };
      const { unmount } = renderWithProviders(<MetricsView />);
      await waitFor(() => expect(windows.length).toBeGreaterThan(0));
      const firstTo = windows[0].to;

      // One bucket (PT1M) later the window must have moved on by exactly one.
      await vi.advanceTimersByTimeAsync(61_000);
      await waitFor(() => expect(windows.at(-1)!.to).not.toBe(firstTo));
      expect(Date.parse(windows.at(-1)!.to) - Date.parse(firstTo)).toBe(60_000);
      unmount();

      windows.length = 0;
      currentSearch = { from: '2026-09-04T09:00:00.000Z', to: '2026-09-04T10:00:00.000Z' };
      renderWithProviders(<MetricsView />);
      await waitFor(() => expect(windows.length).toBeGreaterThan(0));
      await vi.advanceTimersByTimeAsync(300_000);
      expect(new Set(windows.map((w) => w.to)).size).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('lets the operator change the range, which updates the URL', async () => {
    currentSearch = { range: '1h' };
    server.use(
      http.get('*/api/v1/clusters/c1/metrics', () =>
        HttpResponse.json({
          from: '2026-09-04T09:00:00.000Z',
          to: '2026-09-04T10:00:00.000Z',
          step: 'PT1M',
          truncated: false,
          series: [],
        }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    const user = userEvent.setup();
    navigateSpy.mockClear();
    renderWithProviders(<MetricsView />);

    await user.click(await screen.findByRole('radio', { name: '6h' }));
    expect(navigateSpy).toHaveBeenCalledTimes(1);
    // The range is merged into whatever else the URL carries, so a queue scope
    // survives it; an absolute window is what choosing a relative range clears.
    const { search } = navigateSpy.mock.calls[0][0] as {
      search: (prev: Record<string, unknown>) => Record<string, unknown>;
    };
    expect(search({ subject: 'ORDERS', from: 'x', to: 'y' })).toEqual({
      subject: 'ORDERS',
      range: '6h',
      from: undefined,
      to: undefined,
    });
  });
});
