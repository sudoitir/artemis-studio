import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => ({ q: 'orders' }),
  useNavigate: () => () => {},
}));

const { QueuesView } = await import('./QueuesView.tsx');

function queue(name: string) {
  return {
    address: name,
    queueName: name,
    routingType: 'ANYCAST',
    durable: true,
    totalMessageCount: 1,
    totalConsumerCount: 0,
    totalDeliveringCount: 0,
    totalScheduledCount: 0,
    nodesPresent: 1,
    nodesTotal: 1,
    paused: false,
    perNode: [],
  };
}

describe('QueuesView selection', () => {
  it('offers to select every queue matching the filter, and says when it has', async () => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'admin',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({ id: 'c1', name: 'c1', topology: { nodes: [] }, capabilities: {} }),
      ),
      // 140 match, one page of two is shown.
      http.get('*/api/v1/clusters/c1/queues', () =>
        HttpResponse.json({ data: [queue('orders.a'), queue('orders.b')], count: 140, page: 1, pageSize: 200 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select all on this page' }));
    expect(screen.getByText('2 queues selected')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Select all 140 queues matching "orders"' }));
    expect(screen.getByText('All 140 queues matching "orders" are selected.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Clear selection' }));
    expect(screen.queryByText(/selected/)).not.toBeInTheDocument();
  });
});
