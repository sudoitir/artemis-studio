import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { FlowGraphView } from './api.ts';

const navigate = vi.hoisted(() => vi.fn());
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => navigate,
}));

const { FlowInspector } = await import('./FlowInspector.tsx');

const graph = {
  nodes: [
    { id: 'address:ORDERS.inbound', kind: 'ADDRESS', label: 'ORDERS.inbound', routingTypes: ['ANYCAST'], faults: [] },
    {
      id: 'queue:ORDERS.inbound',
      kind: 'QUEUE',
      label: 'ORDERS.inbound',
      messageCount: 1200,
      consumerCount: 0,
      brokerNodes: ['node-a'],
      faults: ['NO_CONSUMER'],
    },
  ],
  edges: [
    {
      id: 'route',
      kind: 'ROUTE',
      source: 'address:ORDERS.inbound',
      target: 'queue:ORDERS.inbound',
      rate: 42,
      rateSource: 'QUEUE_METRIC',
      stale: false,
      delivery: 'SHARED',
      faults: [],
    },
  ],
} as FlowGraphView;

describe('FlowInspector', () => {
  it('explains a queue: figures, faults in words, and the flow into it', () => {
    renderWithProviders(
      <FlowInspector graph={graph} nodeId="queue:ORDERS.inbound" clusterId="c1" onClose={() => {}} onFocus={() => {}} />,
    );

    const details = screen.getByRole('complementary', { name: 'Details of Queue ORDERS.inbound' });
    expect(details).toHaveTextContent('1,200 waiting');
    expect(details).toHaveTextContent('no consumer');
    expect(details).toHaveTextContent('shared · 42 msg/s');
    expect(details).toHaveTextContent('Seen onnode-a');
  });

  it('takes focus on open and closes on Escape', async () => {
    const onClose = vi.fn();
    renderWithProviders(
      <FlowInspector graph={graph} nodeId="queue:ORDERS.inbound" clusterId="c1" onClose={onClose} onFocus={() => {}} />,
    );

    expect(screen.getByRole('button', { name: 'Close details' })).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });

  it('focuses the view and opens the existing screen without mutating anything', async () => {
    const onFocus = vi.fn();
    renderWithProviders(
      <FlowInspector graph={graph} nodeId="queue:ORDERS.inbound" clusterId="c1" onClose={() => {}} onFocus={onFocus} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Focus the view on this' }));
    expect(onFocus).toHaveBeenCalledWith('queue:ORDERS.inbound');

    await userEvent.click(screen.getByRole('button', { name: 'Open in Queues' }));
    expect(navigate).toHaveBeenCalledWith({
      to: '/clusters/$clusterId/queues',
      params: { clusterId: 'c1' },
      search: { q: 'ORDERS.inbound' },
    });
  });
});
