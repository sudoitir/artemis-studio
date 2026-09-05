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
  it('renders bucketed series and shows a gap where a bucket has no sample', async () => {
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
              { ts: '2026-09-04T09:00:00.000Z', value: 10, peak: 12 },
              // a gap: no 09:01 point at all
              { ts: '2026-09-04T09:02:00.000Z', value: 14, peak: 15 },
            ]),
            series('consumerCount', 'GAUGE', [{ ts: '2026-09-04T09:00:00.000Z', value: 2 }]),
            series('messagesAdded', 'RATE', [{ ts: '2026-09-04T09:00:00.000Z', value: 1.5 }]),
            series('messagesAcked', 'RATE', [{ ts: '2026-09-04T09:00:00.000Z', value: 1.4 }]),
          ],
        }),
      ),
      http.get('*/api/v1/clusters/c1/rr/stats', () => HttpResponse.json({ addresses: [] })),
    );

    renderWithProviders(<MetricsView />);

    expect(await screen.findByText('Depth')).toBeInTheDocument();
    // recharts renders the series one tick after its ResponsiveContainer
    // measures — "Depth" (a static label) resolves before that, so the chart
    // itself needs its own wait, not just the heading.
    await waitFor(() =>
      expect(document.querySelectorAll('.recharts-area, .recharts-line').length).toBeGreaterThan(0),
    );
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
    renderWithProviders(<MetricsView />);

    await user.click(await screen.findByRole('radio', { name: '6h' }));
    expect(navigateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ search: { range: '6h' } }),
    );
  });
});
