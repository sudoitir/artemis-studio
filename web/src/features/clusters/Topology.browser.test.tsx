import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen, waitFor } from '@testing-library/react';
import { userEvent } from 'vitest/browser';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { ApiError } from '../../kernel/api/request.ts';
import { keys as authKeys } from '../../kernel/auth/api.ts';
import { FeatureProvider } from '../../kernel/FeatureProvider.tsx';
import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { keys, type HealthView, type NodeEndpointView, type TopologyView } from './api.ts';
import { DENSE_THRESHOLD } from './layout.ts';
import { TopologyView as Page } from './TopologyView.tsx';
import { validateTopologySearch } from './topologySearch.ts';

/**
 * The Topology page in a real browser at the narrowest window the console is designed for (1280 px beside
 * the expanded navigation), in both colour schemes: one h1, no accessibility violations in any layout or
 * state, nothing scrolling sideways, and the canvas's keyboard model on real focus. No network: every query
 * is answered from the seeded cache. React Flow's own style sheet is loaded, as the application loads it: the
 * shared browser setup does not, and without it the boxes are not placed.
 */
beforeAll(() => onlineManager.setOnline(false));
afterAll(() => onlineManager.setOnline(true));

const endpoint = (over: Partial<NodeEndpointView>): NodeEndpointView => ({
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
  urlSource: null,
  urlProblem: null,
  coreUrlManual: false,
  manageable: true,
  ...over,
});

const HEALTH: HealthView = {
  clusterId: 'c1',
  level: 'DEGRADED',
  liveEndpointNames: ['alpha', 'charlie', 'delta-1', 'delta-2'],
  splitBrain: 'CRITICAL',
  replicationBehind: true,
  notes: [],
  credentialRejections: [],
};

/** One pair of each state the page words: in step, behind, a split brain, and an unreachable and an unmanaged pair. */
const TOPOLOGY: TopologyView = {
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
      replicationBehind: true,
      endpoints: [
        endpoint({ id: 'b1', name: 'bravo', artemisNodeId: 'B', version: '2.31.2', versionSupport: 'BELOW_MINIMUM' }),
        endpoint({ id: 'b2', name: 'bravo-backup', haRole: 'BACKUP', active: false, replicaSync: false }),
      ],
    },
    {
      artemisNodeId: 'C',
      splitBrain: 'CRITICAL',
      replicationBehind: false,
      endpoints: [
        endpoint({ id: 'c1', name: 'charlie', artemisNodeId: 'C' }),
        endpoint({ id: 'c2', name: 'charlie-2', artemisNodeId: 'C', haRole: 'BACKUP' }),
      ],
    },
    {
      artemisNodeId: 'D',
      splitBrain: 'NONE',
      replicationBehind: false,
      endpoints: [
        endpoint({ id: 'd1', name: 'delta', artemisNodeId: 'D', active: false, lastError: 'Connection refused' }),
        endpoint({
          id: 'd2',
          name: 'delta:61616',
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

const MANY: TopologyView = {
  clusterId: 'c1',
  nodes: Array.from({ length: DENSE_THRESHOLD + 1 }, (_, i) => {
    const id = `N${String(i).padStart(3, '0')}`;
    return {
      artemisNodeId: id,
      splitBrain: 'NONE',
      replicationBehind: false,
      endpoints: [
        endpoint({ id: `${id}-p`, name: `${id}-primary`, artemisNodeId: id }),
        endpoint({ id: `${id}-b`, name: `${id}-backup`, haRole: 'BACKUP', active: false, replicaSync: true }),
      ],
    };
  }),
};

type Seed = 'data' | 'dense' | 'empty' | 'loading' | 'error';

function seeded(seed: Seed): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } },
  });
  client.setQueryData(authKeys.me, {
    id: 'u1',
    username: 'admin',
    mustChangePassword: false,
    grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
  });
  if (seed === 'loading') return client;
  client.setQueryData(keys.health('c1'), HEALTH);
  if (seed === 'error') {
    client
      .getQueryCache()
      .build(client, { queryKey: keys.topology('c1') })
      .setState({
        status: 'error',
        error: new ApiError(503, { title: 'Broker unreachable', detail: 'No broker answered the management call.' }),
        fetchStatus: 'idle',
      });
    return client;
  }
  const topology = { data: TOPOLOGY, dense: MANY, empty: { clusterId: 'c1', nodes: [] } }[seed];
  client.setQueryData(keys.topology('c1'), topology);
  return client;
}

function page(search: string) {
  const root = createRootRoute();
  const route = createRoute({
    getParentRoute: () => root,
    path: '/clusters/$clusterId/topology',
    validateSearch: validateTopologySearch,
    component: Page,
  });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/clusters/c1/topology${search}`] }),
  });
}

function mount(seed: Seed, search: string, scheme: (typeof SCHEMES)[number], height = 1400) {
  return renderThemed(
    <QueryClientProvider client={seeded(seed)}>
      <FeatureProvider features={[]}>
        <Frame width={contentWidth(1280)} height={height}>
          <RouterProvider router={page(search)} />
        </Frame>
      </FeatureProvider>
    </QueryClientProvider>,
    scheme,
  );
}

const LAYOUTS = [
  { name: 'graph', seed: 'data', search: '', ready: /^alpha: Primary/ },
  { name: 'graph with a chosen node', seed: 'data', search: '?node=d2', ready: /^alpha: Primary/ },
  { name: 'graph with a split brain chosen', seed: 'data', search: '?node=c1', ready: /^alpha: Primary/ },
  { name: 'table', seed: 'data', search: '?view=table', ready: null },
  { name: 'table with a chosen node', seed: 'data', search: '?view=table&node=b2', ready: null },
  { name: 'reduced detail', seed: 'dense', search: '', ready: /^Node N000:/ },
  { name: 'reduced detail as a table', seed: 'dense', search: '?view=table', ready: null },
  { name: 'nothing answered', seed: 'empty', search: '', ready: null },
  { name: 'loading', seed: 'loading', search: '', ready: null },
  { name: 'failed', seed: 'error', search: '', ready: null },
] as const;

describe.each(LAYOUTS)('Topology, $name', ({ seed, search, ready }) => {
  describe.each(SCHEMES)('in the %s scheme at 1280 px', (scheme) => {
    it('has one h1, no accessibility violations and nothing scrolling sideways', async () => {
      const { container } = mount(seed, search, scheme);
      await screen.findByRole('heading', { level: 1, name: 'Topology' });
      if (ready) await screen.findByRole('button', { name: ready });
      else if (search.includes('view=table')) await screen.findByRole('grid', { name: 'Nodes' });
      await settle(() => `${container.querySelectorAll('button, [role="grid"]').length}:${container.scrollHeight}`);

      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
      expect(await axeViolations(container)).toEqual([]);
      expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
    });
  });
});

describe('Topology keyboard, in a real browser', () => {
  const box = (name: RegExp) => screen.getByRole('button', { name });

  it('is one tab stop, moves across and within columns, and keeps the focused box in view', async () => {
    mount('data', '', 'light');
    await screen.findByRole('button', { name: /^alpha: Primary/ });
    await waitFor(() => expect(box(/^alpha: Primary/).tabIndex).toBe(0));
    // Every box has a pressed state, which the view controls beside them do not.
    const stops = screen.getAllByRole('button', { pressed: false }).filter((b) => b.tabIndex === 0);
    expect(stops).toHaveLength(1);

    box(/^alpha: Primary/).focus();
    await userEvent.keyboard('{ArrowDown}');
    expect(box(/^alpha-backup:/)).toHaveFocus();
    await userEvent.keyboard('{ArrowRight}');
    expect(box(/^bravo-backup:/)).toHaveFocus();
    await userEvent.keyboard('{Home}');
    expect(box(/^alpha: Primary/)).toHaveFocus();
    await userEvent.keyboard('{End}');
    const last = box(/^delta:61616:/);
    expect(last).toHaveFocus();

    // Wherever the last box is in the pane, it ends up inside the canvas's frame.
    const frame = last.closest<HTMLElement>('[role="group"]')!;
    await waitFor(() => {
      const box = frame.getBoundingClientRect();
      const at = last.getBoundingClientRect();
      expect(at.left).toBeGreaterThanOrEqual(box.left - 1);
      expect(at.right).toBeLessThanOrEqual(box.right + 1);
      expect(at.top).toBeGreaterThanOrEqual(box.top - 1);
      expect(at.bottom).toBeLessThanOrEqual(box.bottom + 1);
    });
  });

  it('keeps every box in a window as tall as a laptop, at least 24 px square', async () => {
    mount('data', '', 'dark');
    await screen.findByRole('button', { name: /^alpha: Primary/ });
    for (const b of screen.getAllByRole('button', { name: /^(alpha|bravo|charlie|delta)/ })) {
      const r = b.getBoundingClientRect();
      expect(r.width).toBeGreaterThanOrEqual(24);
      expect(r.height).toBeGreaterThanOrEqual(24);
    }
  });

  it('chooses with Enter, writing the node into the address, and clears with Escape', async () => {
    const router = page('');
    renderThemed(
      <QueryClientProvider client={seeded('data')}>
        <FeatureProvider features={[]}>
          <Frame width={contentWidth(1280)} height={1400}>
            <RouterProvider router={router} />
          </Frame>
        </FeatureProvider>
      </QueryClientProvider>,
      'light',
    );
    await screen.findByRole('button', { name: /^alpha: Primary/ });
    box(/^alpha-backup:/).focus();
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(router.state.location.search).toEqual({ node: 'a2' }));
    expect(await screen.findByRole('heading', { level: 2, name: 'alpha-backup' })).toBeInTheDocument();
    expect(box(/^alpha-backup:/)).toHaveAttribute('aria-pressed', 'true');
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(router.state.location.search).toEqual({}));
    expect(box(/^alpha-backup:/)).toHaveFocus();
  });
});
