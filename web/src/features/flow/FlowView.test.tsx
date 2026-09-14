import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const routerState = vi.hoisted(() => ({ search: {} as Record<string, unknown>, navigate: vi.fn() }));

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => routerState.search,
  useNavigate: () => routerState.navigate,
}));

const { FlowView } = await import('./FlowView.tsx');

function graph(over: Record<string, unknown> = {}) {
  return {
    nodes: [
      { id: 'address:orders', kind: 'ADDRESS', label: 'orders', routingTypes: ['ANYCAST'], faults: [] },
      { id: 'queue:ORDERS.inbound', kind: 'QUEUE', label: 'orders', messageCount: 1200, consumerCount: 0, faults: ['NO_CONSUMER'] },
      { id: 'producer:order-svc', kind: 'PRODUCER', label: 'order-svc', members: 2, faults: [] },
    ],
    edges: [
      {
        id: 'route:orders->orders',
        kind: 'ROUTE',
        source: 'address:orders',
        target: 'queue:ORDERS.inbound',
        rate: 42,
        rateSource: 'QUEUE_METRIC',
        asOf: '2026-09-14T10:00:00Z',
        stale: false,
        delivery: 'SHARED',
        faults: [],
      },
      {
        id: 'produce:order-svc->orders',
        kind: 'PRODUCE',
        source: 'producer:order-svc',
        target: 'address:orders',
        rateSource: 'SAMPLER',
        stale: false,
        members: 2,
        faults: [],
      },
    ],
    kpis: { inRate: 42, outRate: null, backlog: 1200, clients: 1, faults: 2 },
    totals: { paths: 5, shown: 2, limit: 40, clamped: false },
    focus: null,
    sampledAt: null,
    measuring: true,
    sampleIntervalSeconds: 15,
    brokerNodes: [
      {
        nodeId: 'n2',
        name: 'node-b',
        state: 'UNREACHABLE',
        message: 'This node did not answer the latest sweep, so its clients are not shown.',
        producersSeen: 0,
        producersTotal: 0,
        consumersSeen: 0,
        consumersTotal: 0,
        truncated: false,
      },
    ],
    ...over,
  };
}

function serve(body: Record<string, unknown>) {
  server.use(http.get('*/api/v1/clusters/c1/flow', () => HttpResponse.json(body)));
}

describe('FlowView', () => {
  beforeEach(() => {
    routerState.search = {};
    routerState.navigate.mockReset();
  });

  it('leads with totals, states an unknown total as measuring, and states its bound', async () => {
    serve(graph());
    renderWithProviders(<FlowView />);

    const totals = await screen.findByRole('region', { name: 'Totals across every path' });
    expect(totals).toHaveTextContent('42 msg/s');
    expect(totals).toHaveTextContent('Messages outmeasuring…');
    expect(totals).toHaveTextContent('2 faults');
    expect(screen.getByText('Showing 2 of 5 paths, ranked by messages in.')).toBeInTheDocument();
    expect(screen.getByText('Raise the limit, or focus a client, address or queue to reach the rest.')).toBeInTheDocument();
    expect(screen.getByText(/Measuring client rates/)).toBeInTheDocument();
  });

  it('names a node that did not answer instead of showing fewer clients silently', async () => {
    serve(graph());
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('node-b did not answer')).toBeInTheDocument();
    expect(screen.getByText(/its clients are not shown/)).toBeInTheDocument();
  });

  it('shows the management access that grants a refused listing', async () => {
    serve(
      graph({
        brokerNodes: [
          {
            nodeId: 'n1',
            name: 'node-a',
            state: 'PERMISSION_DENIED',
            message: 'The broker refused to list producers or consumers.',
            brokerXmlSnippet: '<role-access><match domain="org.apache.activemq.artemis"/></role-access>',
            truncated: false,
          },
        ],
      }),
    );
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('node-a refused to list clients')).toBeInTheDocument();
    expect(screen.getByText(/role-access/)).toBeInTheDocument();
  });

  it('teaches what flow is when the cluster has nothing to draw', async () => {
    serve(
      graph({
        nodes: [],
        edges: [],
        kpis: { inRate: null, outRate: null, backlog: 0, clients: 0, faults: 0 },
        totals: { paths: 0, shown: 0, limit: 40, clamped: false },
        brokerNodes: [],
      }),
    );
    renderWithProviders(<FlowView />);

    expect(await screen.findByRole('heading', { name: 'No flow to show yet' })).toBeInTheDocument();
  });

  it('says when a focus matches nothing and clears it through the URL', async () => {
    routerState.search = { focus: 'queue:ARCHIVE.gone' };
    serve(
      graph({
        nodes: [],
        edges: [],
        totals: { paths: 5, shown: 0, limit: 40, clamped: false },
        focus: { kind: 'queue', name: 'ARCHIVE.gone', hops: 1, matched: false },
        brokerNodes: [],
      }),
    );
    renderWithProviders(<FlowView />);

    expect(await screen.findByText('Nothing matches the focus queue ARCHIVE.gone')).toBeInTheDocument();
    await userEvent.click(screen.getAllByRole('button', { name: 'Clear focus' })[0]);

    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({ focus: 'queue:ARCHIVE.gone', rank: 'OUT' })).toEqual({ focus: undefined, hops: undefined, rank: 'OUT' });
  });

  it('draws no moving dots when the system asks for reduced motion, and says why', async () => {
    const original = window.matchMedia;
    window.matchMedia = ((query: string) => ({
      ...original(query),
      matches: query.includes('prefers-reduced-motion'),
    })) as typeof window.matchMedia;
    try {
      serve(graph({ measuring: false, sampledAt: '2026-09-14T10:00:00Z' }));
      const { container } = renderWithProviders(<FlowView />);

      expect(await screen.findByText(/Motion is off \(reduced motion\)/)).toBeInTheDocument();
      expect(screen.getByText('Motion off: your system asks for reduced motion.')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Pause motion' })).not.toBeInTheDocument();
      expect(container.querySelector('animateMotion')).toBeNull();
    } finally {
      window.matchMedia = original;
    }
  });

  it('offers ranking, grouping and the bound as labelled controls', async () => {
    routerState.search = { tab: 'table' };
    serve(graph());
    renderWithProviders(<FlowView />);

    // Mantine names both the input and its option list by the label; the input is the control.
    const input = (label: string) =>
      screen.getAllByLabelText(label).find((el) => el.tagName === 'INPUT') as HTMLInputElement;
    await screen.findAllByLabelText('Rank paths by');
    expect(input('Rank paths by')).toHaveValue('messages in');
    expect(input('Group clients by')).toHaveValue('Client ID');
    expect(input('Show')).toHaveValue('40 busiest paths');
    // The controls render before the graph arrives; the table only once it has.
    const headers = await screen.findAllByRole('columnheader');
    expect(headers.map((h) => h.textContent?.trim())).toEqual(
      expect.arrayContaining(['From', 'Relation', 'To', 'Rate', 'Rate from', 'Clients', 'Faults']),
    );
  });
});
