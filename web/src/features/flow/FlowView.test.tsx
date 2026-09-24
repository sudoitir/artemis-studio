import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
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

function serve(body: Record<string, unknown>, seen: string[] = []) {
  server.use(
    http.get('*/api/v1/clusters/c1/flow', ({ request }) => {
      seen.push(request.url);
      return HttpResponse.json(body);
    }),
  );
}

/** The queue's node shares: a stranded backlog on artemis-b, and artemis-c silent. */
function splitGraph() {
  const base = graph({ measuring: false, sampledAt: '2026-09-14T10:00:00Z' });
  const nodes = (base.nodes as Array<Record<string, unknown>>).map((n) =>
    n.kind === 'QUEUE'
      ? {
          ...n,
          byNode: [
            { nodeId: 'a', node: 'artemis-a', messageCount: 10, consumerCount: 3, inRate: 5, outRate: 5, stale: false },
            { nodeId: 'b', node: 'artemis-b', messageCount: 9000, consumerCount: 0, inRate: 5, outRate: 0, stale: false },
            { nodeId: 'c', node: 'artemis-c', messageCount: null, consumerCount: null, inRate: null, outRate: null, stale: true },
          ],
        }
      : n,
  );
  return { ...base, nodes };
}

function serveHistory() {
  const points = [{ ts: '2026-09-14T10:00:00Z', value: 10 }];
  const series = (metric: string) => ({ metric, kind: 'GAUGE', unit: 'messages', points });
  server.use(
    http.get('*/api/v1/clusters/c1/metrics', () =>
      HttpResponse.json({
        from: '2026-09-14T09:00:00Z',
        to: '2026-09-14T10:00:00Z',
        step: 'PT1M',
        truncated: false,
        series: [],
        splitBy: 'NODE',
        byNode: [
          { nodeId: 'a', nodeName: 'artemis-a', sampled: true, series: ['messageCount', 'messagesAdded', 'messagesAcked'].map(series) },
          { nodeId: 'b', nodeName: 'artemis-b', sampled: false, series: [] },
        ],
      }),
    ),
  );
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

  it('folds the overview map away and remembers the choice', async () => {
    // The minimap covers the corner of a graph the size of a real estate, and
    // the operator's choice has to survive the next visit to be worth making.
    window.localStorage.removeItem('artemis-studio.flow.minimap');
    serve(graph());
    const { unmount } = renderWithProviders(<FlowView />);

    expect(await screen.findByRole('img', { name: 'Overview of the whole graph' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Hide overview' }));
    expect(screen.queryByRole('img', { name: 'Overview of the whole graph' })).not.toBeInTheDocument();
    unmount();

    renderWithProviders(<FlowView />);
    expect(await screen.findByRole('button', { name: 'Show overview' })).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'Overview of the whole graph' })).not.toBeInTheDocument();
  });

  it('turns a routing layer on through the URL', async () => {
    serve(graph());
    renderWithProviders(<FlowView />);

    await userEvent.click(await screen.findByRole('checkbox', { name: 'Dead letter & expiry' }));

    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({})).toEqual({ layers: 'BRIDGES,CLUSTER,DEAD_LETTER,DIVERTS' });
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

  it('opens the Split layout through the URL, and only it asks for the per-node breakdown', async () => {
    const seen: string[] = [];
    serve(graph(), seen);
    renderWithProviders(<FlowView />);

    await userEvent.click(await screen.findByRole('radio', { name: 'Split' }));
    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({ rank: 'OUT' })).toEqual({ rank: 'OUT', tab: 'split' });
    expect(seen.every((url) => !url.includes('byNode'))).toBe(true);
  });

  it('restores the selection from the address, states imbalance in words, and never reads a silent node as zero', async () => {
    routerState.search = { tab: 'split', node: 'queue:ORDERS.inbound' };
    const seen: string[] = [];
    serve(splitGraph(), seen);
    serveHistory();
    renderWithProviders(<FlowView />);

    const pane = await screen.findByRole('region', { name: 'Queue orders per node' });
    expect(seen.at(-1)).toContain('byNode=true');
    expect(pane).toHaveTextContent('artemis-b holds 9,000 messages and has no consumer; the consumers are on artemis-a.');
    expect(pane).toHaveTextContent('artemis-c did not answer, so its share is unknown and left out.');
    const grid = screen.getByRole('grid', { name: 'orders per node' });
    const silent = (await within(grid).findByText('artemis-c')).closest('[role="row"]') as HTMLElement;
    expect(silent).toHaveTextContent('did not answer');
    expect(within(silent).getAllByText('unknown')).toHaveLength(4);

    // The trends are the metrics feature's, per node; a node without samples says so.
    expect(await screen.findByRole('region', { name: 'History on artemis-a' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'History on artemis-b' })).toHaveTextContent('Not sampled in this window');
  });

  it('clears the selection through the address', async () => {
    routerState.search = { tab: 'split', node: 'queue:ORDERS.inbound' };
    serve(splitGraph());
    serveHistory();
    renderWithProviders(<FlowView />);

    await userEvent.click(await screen.findByRole('button', { name: 'Clear selection' }));
    const call = routerState.navigate.mock.calls.at(-1)?.[0] as { search: (prev: object) => Record<string, unknown> };
    expect(call.search({ tab: 'split', node: 'queue:ORDERS.inbound' })).toEqual({ tab: 'split', node: undefined });
  });

  it('opens the inspector for a selection in the address in the graph layout', async () => {
    routerState.search = { node: 'queue:ORDERS.inbound' };
    serve(graph());
    renderWithProviders(<FlowView />);

    expect(await screen.findByRole('complementary', { name: 'Details of Queue orders' })).toBeInTheDocument();
  });

  it('resizes the monitoring pane from the keyboard', async () => {
    routerState.search = { tab: 'split' };
    serve(splitGraph());
    renderWithProviders(<FlowView />);

    const separator = await screen.findByRole('separator', { name: 'Resize the monitoring pane' });
    const before = Number(separator.getAttribute('aria-valuenow'));
    separator.focus();
    await userEvent.keyboard('{ArrowLeft}');
    expect(Number(separator.getAttribute('aria-valuenow'))).toBeLessThan(before);
    // With nothing selected, the pane shows each broker node's totals.
    expect(screen.getByRole('grid', { name: 'Totals per broker node' })).toBeInTheDocument();
  });

  it("offers a path's resource to open, and nothing that changes the broker", async () => {
    routerState.search = { tab: 'table' };
    serve(graph());
    renderWithProviders(<FlowView />);

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for order-svc' }));
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByRole('menuitem', { name: /Focus the view on this/ })).toBeInTheDocument();
    expect(within(menu).getByRole('menuitem', { name: /Open its connections/ })).toBeInTheDocument();
    expect(within(menu).queryByRole('menuitem', { name: /Delete|Purge|Close/ })).not.toBeInTheDocument();
    expect(within(menu).getByText(/This view never changes the broker/)).toBeInTheDocument();
  });
});
