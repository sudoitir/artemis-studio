import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

const navigate = vi.fn();

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
  useParams: () => ({ clusterId: 'c1' }),
}));

// Imported after the mock is registered.
const { CommandPalette } = await import('./CommandPalette.tsx');

function mockApi() {
  server.use(
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json([
        {
          id: 'c1',
          name: 'prod-eu',
          description: null,
          health: 'OK',
          nodeCount: 2,
          updatedAt: new Date().toISOString(),
        },
      ]),
    ),
    http.get('*/api/v1/clusters/c1/queues', () =>
      HttpResponse.json({
        data: [
          {
            address: 'ORDERS',
            queueName: 'ORDERS',
            routingType: 'ANYCAST',
            durable: true,
            totalMessageCount: 7,
            totalConsumerCount: 1,
            totalDeliveringCount: 0,
            totalScheduledCount: 0,
            nodesPresent: 1,
            nodesTotal: 2,
            perNode: [],
          },
        ],
        count: 1,
        page: 1,
        pageSize: 50,
      }),
    ),
  );
}

describe('CommandPalette', () => {
  it('opens on mod+K, filters, and navigates when an action is invoked', async () => {
    navigate.mockClear();
    mockApi();
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    await user.keyboard('{Control>}k{/Control}');

    const search = await screen.findByPlaceholderText(/jump to a cluster/i);
    await user.type(search, 'prod-eu');

    const action = await screen.findByRole('button', { name: /prod-eu/i });
    await user.click(action);

    expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/topology' });
  });

  it('lists a queue action that navigates with the queue name as search', async () => {
    navigate.mockClear();
    mockApi();
    const user = userEvent.setup();
    renderWithProviders(<CommandPalette />);

    await user.keyboard('{Control>}k{/Control}');
    const search = await screen.findByPlaceholderText(/jump to a cluster/i);
    await user.type(search, 'ORDERS');

    const action = await screen.findByRole('button', { name: /ORDERS/i });
    await user.click(action);

    expect(navigate).toHaveBeenCalledWith({
      to: '/clusters/c1/queues',
      search: { q: 'ORDERS' },
    });
  });
});
