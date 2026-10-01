import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { HealthView, NodeEndpointView, TopologyView } from './api.ts';
import { DENSE_THRESHOLD, layout } from './layout.ts';

const centre = vi.hoisted(() => vi.fn());
/** Where the box in focus sits in the pane, and the frame it is looked at through, since jsdom lays nothing out. */
const pane = vi.hoisted(() => ({ x: 0, y: 0 }));

vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>();
  return {
    ...actual,
    useReactFlow: () => ({
      ...actual.useReactFlow(),
      setCenter: centre,
      getInternalNode: () => ({ internals: { positionAbsolute: { x: pane.x, y: pane.y } } }),
    }),
    useStoreApi: () => ({ getState: () => ({ width: 1000, height: 600, transform: [0, 0, 1] }) }),
  };
});

const { TopologyCanvas } = await import('./TopologyCanvas.tsx');

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

const HEALTH: HealthView = {
  clusterId: 'c',
  level: 'OK',
  liveEndpointNames: [],
  splitBrain: 'NONE',
  replicationBehind: false,
  notes: [],
};

/** Two pairs, A (primary above backup) and B (a primary whose second node was found but not given a URL). */
const TOPOLOGY: TopologyView = {
  clusterId: 'c',
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
        endpoint({ id: 'b2', name: 'bravo:61616', active: false, jolokiaUrl: null, manageable: false }),
      ],
    },
  ],
};

const model = layout(TOPOLOGY, HEALTH);
const names = {
  a1: /^alpha: Primary\. Live, serving\./,
  a2: /^alpha-backup: Backup\. Backup, replicating, in sync\./,
  b1: /^bravo: Primary\./,
  b2: /^bravo:61616: Primary\. Not polled: no management URL\./,
};

function draw(props: Partial<React.ComponentProps<typeof TopologyCanvas>> = {}) {
  const onSelect = vi.fn();
  const onShowTable = vi.fn();
  renderWithProviders(<TopologyCanvas model={model} onSelect={onSelect} onShowTable={onShowTable} {...props} />);
  return { onSelect, onShowTable };
}

const box = (name: RegExp) => screen.findByRole('button', { name });

beforeEach(() => {
  centre.mockClear();
  pane.x = 0;
  pane.y = 0;
});

describe('TopologyCanvas boxes', () => {
  it('are buttons named by one sentence of words, and an unmanaged box holds no second button', async () => {
    draw();
    for (const name of Object.values(names)) expect(await box(name)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add a management URL/ })).toBeNull();
    expect(screen.getAllByRole('button', { name: /^(alpha|bravo)/ })).toHaveLength(4);
  });

  it('show four lines: name and version, liveness, role and pair, and the address', async () => {
    draw();
    const b = await box(names.a1);
    expect(b).toHaveTextContent('alpha');
    expect(b).toHaveTextContent('2.44.0');
    expect(b).toHaveTextContent('Live, serving');
    expect(b).toHaveTextContent('Primary · paired with alpha-backup, in sync');
    expect(b).toHaveTextContent('node:8161');
    expect(await box(names.b2)).toHaveTextContent('Not polled');
    expect(await box(names.b2)).toHaveTextContent('node:61616'.replace('node', 'node'));
  });

  it('mark the chosen node', async () => {
    draw({ selectedId: 'a2' });
    expect(await box(names.a2)).toHaveAttribute('aria-pressed', 'true');
    expect(await box(names.a1)).toHaveAttribute('aria-pressed', 'false');
  });
});

describe('TopologyCanvas keyboard', () => {
  async function ready() {
    const view = draw();
    await box(names.a1);
    await waitFor(() =>
      expect(screen.getAllByRole('button', { name: /^(alpha|bravo)/ }).filter((b) => b.tabIndex === 0)).toHaveLength(1),
    );
    return { ...view, user: userEvent.setup() };
  }

  it('is one tab stop, landing on the first box', async () => {
    const { user } = await ready();
    await user.tab();
    expect(await box(names.a1)).toHaveFocus();
  });

  it('lands on the chosen box when one is chosen', async () => {
    draw({ selectedId: 'b1' });
    await box(names.b1);
    await waitFor(() => expect(screen.getByRole('button', { name: names.b1 }).tabIndex).toBe(0));
    expect(screen.getByRole('button', { name: names.a1 }).tabIndex).toBe(-1);
  });

  it('moves down and up within a column and across columns with the arrows', async () => {
    const { user } = await ready();
    await user.tab();
    await user.keyboard('{ArrowDown}');
    expect(await box(names.a2)).toHaveFocus();
    await user.keyboard('{ArrowDown}');
    expect(await box(names.a2)).toHaveFocus();
    await user.keyboard('{ArrowRight}');
    expect(await box(names.b2)).toHaveFocus();
    await user.keyboard('{ArrowUp}');
    expect(await box(names.b1)).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(await box(names.a1)).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(await box(names.a1)).toHaveFocus();
  });

  it('goes to the first and last box with Home and End', async () => {
    const { user } = await ready();
    await user.tab();
    await user.keyboard('{End}');
    expect(await box(names.b2)).toHaveFocus();
    await user.keyboard('{Home}');
    expect(await box(names.a1)).toHaveFocus();
  });

  it('selects with Enter and with Space, and announces the node', async () => {
    const { user, onSelect } = await ready();
    await user.tab();
    await user.keyboard('{ArrowDown}{Enter}');
    expect(onSelect).toHaveBeenLastCalledWith('a2');
    expect(screen.getByRole('status', { name: '' })).toHaveTextContent(/^Selected alpha-backup: Backup\./);
    await user.keyboard('{ArrowRight} ');
    expect(onSelect).toHaveBeenLastCalledWith('b2');
  });

  it('clears the selection with Escape and keeps focus on the box', async () => {
    const { onSelect } = draw({ selectedId: 'a1' });
    const user = userEvent.setup();
    const first = await box(names.a1);
    await waitFor(() => expect(first.tabIndex).toBe(0));
    await user.tab();
    expect(first).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(onSelect).toHaveBeenCalledWith(null);
    expect(first).toHaveFocus();
    expect(screen.getAllByRole('status').some((s) => s.textContent === 'Selection cleared')).toBe(true);
  });

  it('keeps a box that is out of view in view with setCenter, without animation', async () => {
    pane.x = 5000;
    const { user } = await ready();
    await user.tab();
    await waitFor(() => expect(centre).toHaveBeenCalled());
    expect(centre).toHaveBeenLastCalledWith(5000 + 130, 56, { zoom: 1, duration: 0 });
  });

  it('does not move the view for a box that is already in it', async () => {
    pane.x = 100;
    pane.y = 100;
    const { user } = await ready();
    await user.tab();
    await user.keyboard('{ArrowRight}');
    expect(await box(names.b1)).toHaveFocus();
    expect(centre).not.toHaveBeenCalled();
  });

  it('leaves the arrow keys alone on the view controls', async () => {
    const { user } = await ready();
    await user.click(await screen.findByRole('button', { name: 'Zoom in' }));
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('button', { name: 'Zoom in' })).toHaveFocus();
  });
});

describe('TopologyCanvas preview', () => {
  it('draws the same boxes as plain elements, with no controls, legend or keyboard model', async () => {
    draw({ interactive: false });
    expect(await screen.findByText('alpha')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('group', { name: 'Cluster topology' })).toBeNull();
    expect(screen.queryByText(/serving above, standby below/)).toBeNull();
  });
});

describe('TopologyCanvas level of detail', () => {
  const many: TopologyView = {
    clusterId: 'c',
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

  it('states the bound with a neutral notice and a way to the table', async () => {
    const { onShowTable } = draw({ model: layout(many, HEALTH) });
    expect(await screen.findByText('One box per pair')).toBeInTheDocument();
    expect(screen.getByText(/25 logical nodes, more than the 24 drawn in full/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Show as table' }));
    expect(onShowTable).toHaveBeenCalled();
  });

  it('draws each pair as one box that chooses its serving endpoint', async () => {
    const { onSelect } = draw({ model: layout(many, HEALTH) });
    const first = await screen.findByRole('button', { name: /^Node N000: serving · 1 standby\. 2 endpoints\.$/ });
    // A bare click: jsdom's pointer events carry no window, which the pane's pan handler reads.
    fireEvent.click(first);
    expect(onSelect).toHaveBeenCalledWith('N000-p');
  });

  it('draws no notice at the bound', async () => {
    draw({ model: layout({ ...many, nodes: many.nodes.slice(0, DENSE_THRESHOLD) }, HEALTH) });
    await box(/^N000-primary:/);
    expect(screen.queryByText('One box per pair')).toBeNull();
  });
});

describe('TopologyCanvas colour scheme', () => {
  it('hands React Flow the scheme Mantine resolved', async () => {
    const { container } = renderWithProviders(<TopologyCanvas model={model} />);
    await box(names.a1);
    expect(container.querySelector('.react-flow')).toHaveClass('dark');
  });
});
