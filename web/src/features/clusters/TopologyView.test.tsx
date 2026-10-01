import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { HealthView, NodeEndpointView, TopologyView as Topology } from './api.ts';

const routerState = vi.hoisted(() => ({ search: {} as Record<string, unknown>, navigate: vi.fn() }));

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => routerState.search,
  useNavigate: () => routerState.navigate,
}));

const { TopologyView } = await import('./TopologyView.tsx');

function endpoint(over: Partial<NodeEndpointView>): NodeEndpointView {
  return {
    id: 'e',
    name: 'node',
    artemisNodeId: 'NID',
    jolokiaUrl: 'http://node:8161/jolokia',
    coreUrl: 'node:61616',
    haRole: 'PRIMARY',
    state: 'STARTED',
    active: true,
    replicaSync: null,
    version: '2.44.0',
    versionSupport: 'SUPPORTED',
    lastError: null,
    lastSeenAt: '2026-09-30T14:00:00Z',
    discovered: true,
    manualOverride: false,
    manageable: true,
    ...over,
  };
}

const TOPOLOGY: Topology = {
  clusterId: 'c1',
  nodes: [
    {
      artemisNodeId: 'A',
      splitBrain: 'NONE',
      replicationBehind: false,
      endpoints: [
        endpoint({ id: 'a1', name: 'alpha', artemisNodeId: 'A' }),
        endpoint({ id: 'a2', name: 'alpha-backup', haRole: 'BACKUP', active: false, replicaSync: true }),
      ],
    },
    {
      artemisNodeId: 'B',
      splitBrain: 'NONE',
      replicationBehind: false,
      endpoints: [
        endpoint({ id: 'b1', name: 'bravo', artemisNodeId: 'B' }),
        endpoint({
          id: 'b2',
          name: 'bravo:61616',
          haRole: 'BACKUP',
          active: false,
          jolokiaUrl: null,
          manageable: false,
          lastSeenAt: null,
        }),
      ],
    },
  ],
};

const HEALTH: HealthView = {
  clusterId: 'c1',
  level: 'OK',
  liveEndpointNames: ['alpha', 'bravo'],
  splitBrain: 'NONE',
  replicationBehind: false,
  notes: [],
};

function serve(topology: Topology = TOPOLOGY, health: HealthView = HEALTH) {
  server.use(
    http.get('*/api/v1/clusters/c1/topology', () => HttpResponse.json(topology)),
    http.get('*/api/v1/clusters/c1/health', () => HttpResponse.json(health)),
    http.get('*/api/v1/clusters/c1/alerts/firing', () => HttpResponse.json(paged([]))),
  );
}

beforeEach(() => {
  routerState.search = {};
  routerState.navigate.mockClear();
});

describe('TopologyView', () => {
  it('is one h1, a Graph and Table choice, and every node as a box named in words', async () => {
    serve();
    renderWithProviders(<TopologyView />);
    expect(screen.getByRole('heading', { level: 1, name: 'Topology' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /^alpha: Primary\. Live, serving\./ })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /^bravo:61616: Backup\. Not polled: no management URL\./ }),
    ).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Graph' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Table' })).not.toBeChecked();
  });

  it('holds the place of the graph while it loads, then settles', async () => {
    serve();
    renderWithProviders(<TopologyView />);
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading the topology')).toBeInTheDocument();
    await screen.findByRole('button', { name: /^alpha:/ });
    expect(document.querySelector('[aria-busy="true"]')).toBeNull();
  });

  it('asks before choosing anything: the panel says how to choose a node', async () => {
    serve();
    renderWithProviders(<TopologyView />);
    await screen.findByRole('button', { name: /^alpha:/ });
    expect(screen.getByRole('heading', { level: 2, name: 'Node details' })).toBeInTheDocument();
  });

  it('puts the chosen node in the address', async () => {
    serve();
    renderWithProviders(<TopologyView />);
    const box = await screen.findByRole('button', { name: /^alpha-backup:/ });
    // A bare click: jsdom's pointer events carry no window, which the pane's pan handler reads.
    fireEvent.click(box);
    expect(routerState.navigate).toHaveBeenCalled();
    const { search } = routerState.navigate.mock.calls.at(-1)![0] as {
      search: (prev: Record<string, unknown>) => Record<string, unknown>;
    };
    expect(search({ view: 'table' })).toEqual({ view: 'table', node: 'a2' });
  });

  it('puts the table in the address, and leaves the graph out of it', async () => {
    serve();
    renderWithProviders(<TopologyView />);
    await screen.findByRole('button', { name: /^alpha:/ });
    await userEvent.setup().click(screen.getByRole('radio', { name: 'Table' }));
    const { search } = routerState.navigate.mock.calls.at(-1)![0] as {
      search: (prev: Record<string, unknown>) => Record<string, unknown>;
    };
    expect(search({})).toEqual({ view: 'table' });
  });

  it('shows the chosen node in a panel: its facts, its pair, and no action for a managed node', async () => {
    routerState.search = { node: 'a2' };
    serve();
    renderWithProviders(<TopologyView />);
    const panel = await screen.findByRole('complementary', { name: 'Chosen node' });
    expect(await within(panel).findByRole('heading', { level: 2, name: 'alpha-backup' })).toBeInTheDocument();
    const facts = within(panel).getByRole('group', { name: 'Node facts' });
    for (const [term, value] of [
      ['Role', 'Backup'],
      ['Liveness', 'Backup, replicating, in sync'],
      ['Pair', 'paired with alpha, in sync'],
      ['Version', '2.44.0'],
      ['Node ID', 'NID'],
      ['Management URL', 'http://node:8161/jolokia'],
      ['Core URL', 'node:61616'],
      ['Last error', 'None'],
      ['How found', 'Discovered from the cluster'],
    ]) {
      expect(within(facts).getByText(term).nextElementSibling).toHaveTextContent(value);
    }
    expect(within(facts).getByText('Last seen').nextElementSibling).toHaveTextContent(/ago/);
    expect(within(panel).getByRole('heading', { level: 3, name: 'Its pair' })).toBeInTheDocument();
    expect(within(panel).getByText('1 of 2 nodes serving')).toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: 'Add a management URL' })).toBeNull();
  });

  it('offers to add a management URL for a node that is not polled, and opens the flow', async () => {
    routerState.search = { node: 'b2' };
    serve();
    renderWithProviders(<TopologyView />);
    const panel = await screen.findByRole('complementary', { name: 'Chosen node' });
    expect(await within(panel).findByText('Not polled: no management URL')).toBeInTheDocument();
    expect(within(panel).getByText('Not seen yet')).toBeInTheDocument();
    await userEvent.setup().click(within(panel).getByRole('button', { name: 'Add a management URL' }));
    expect(await screen.findByRole('dialog', { name: /Add a management URL for node:61616/ })).toBeInTheDocument();
  });

  it('says so when the address names a node the cluster does not have', async () => {
    routerState.search = { node: 'gone' };
    serve();
    renderWithProviders(<TopologyView />);
    expect(await screen.findByText('Node not found')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Clear selection' }));
    const { search } = routerState.navigate.mock.calls.at(-1)![0] as {
      search: (prev: Record<string, unknown>) => Record<string, unknown>;
    };
    expect(search({ node: 'gone' })).toEqual({ node: undefined });
  });

  it('lists the same nodes as a table, with their facts in words', async () => {
    routerState.search = { view: 'table' };
    serve();
    renderWithProviders(<TopologyView />);
    const grid = await screen.findByRole('grid', { name: 'Nodes' });
    expect(screen.getByRole('radio', { name: 'Table' })).toBeChecked();
    expect(await within(grid).findByRole('rowheader', { name: 'alpha' })).toBeInTheDocument();
    for (const name of ['alpha-backup', 'bravo', 'bravo:61616']) {
      expect(within(grid).getByRole('rowheader', { name })).toBeInTheDocument();
    }
    expect(within(grid).getAllByText('Live, serving')).toHaveLength(2);
    expect(within(grid).getByText('Backup, replicating, in sync')).toBeInTheDocument();
    expect(within(grid).getByText('Not polled: no management URL')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Cluster topology' })).toBeNull();
  });

  it('chooses a node from its row in the table', async () => {
    routerState.search = { view: 'table' };
    serve();
    renderWithProviders(<TopologyView />);
    const row = await screen.findByRole('rowheader', { name: 'bravo' });
    await userEvent.setup().click(row);
    const { search } = routerState.navigate.mock.calls.at(-1)![0] as {
      search: (prev: Record<string, unknown>) => Record<string, unknown>;
    };
    expect(search({ view: 'table' })).toEqual({ view: 'table', node: 'b1' });
  });

  it('teaches what to do when no node has answered', async () => {
    serve({ clusterId: 'c1', nodes: [] });
    renderWithProviders(<TopologyView />);
    expect(await screen.findByText('No node has answered yet')).toBeInTheDocument();
    expect(screen.getByText(/Studio learns the topology from the first broker it reaches/)).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Cluster topology' })).toBeNull();
  });

  it('says why it failed and offers to try again', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/topology', () =>
        HttpResponse.json({ title: 'Broker unreachable', status: 503 }, { status: 503 }),
      ),
      http.get('*/api/v1/clusters/c1/health', () => HttpResponse.json(HEALTH)),
    );
    renderWithProviders(<TopologyView />);
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Cluster topology' })).toBeNull();
  });

  it('settles on its error while the query is still retrying, with nothing left busy', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/topology', () => HttpResponse.json({ status: 503 }, { status: 503 })),
      http.get('*/api/v1/clusters/c1/health', () => HttpResponse.json(HEALTH)),
    );
    // The application's own retry policy: a server error is tried again, with a pause between tries.
    const retrying = new QueryClient({ defaultOptions: { queries: { retry: 3, retryDelay: 60_000 } } });
    renderWithProviders(
      <QueryClientProvider client={retrying}>
        <TopologyView />
      </QueryClientProvider>,
    );
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await waitFor(() => expect(document.querySelector('[aria-busy="true"], .mantine-Loader-root')).toBeNull());
  });

  it('keeps showing the topology through a failed refresh', async () => {
    serve();
    const { client } = renderWithProviders(<TopologyView />);
    await screen.findByRole('button', { name: /^alpha:/ });
    server.use(http.get('*/api/v1/clusters/c1/topology', () => HttpResponse.json({ status: 500 }, { status: 500 })));
    await client.refetchQueries({ queryKey: ['clusters', 'c1', 'topology'] });
    expect(screen.getByRole('button', { name: /^alpha:/ })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
