import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ComponentType } from 'react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { BridgeView, CapabilityView, DivertView } from './api.ts';

let search: Record<string, unknown> = {};
const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => navigate,
}));

/** The tabs other features contribute; the real routing builder is not what is under test here. */
let contributed: { id: string; order: number; title: string; Component: ComponentType<{ clusterId: string }> }[] = [];
vi.mock('../../kernel/slots.ts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../kernel/slots.ts')>()),
  useSlot: (name: string) => (name === 'routing.tabs' ? contributed : []),
}));

const { RoutingView } = await import('./RoutingView.tsx');

const AVAILABLE: CapabilityView = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function divert(over: Partial<DivertView> = {}): DivertView {
  return {
    name: 'copy-orders',
    address: 'orders',
    forwardingAddress: 'orders.audit',
    exclusive: false,
    retroactiveResource: false,
    nodesPresent: 2,
    nodesTotal: 2,
    perNode: [],
    ...over,
  };
}

function bridge(over: Partial<BridgeView> = {}): BridgeView {
  return {
    name: 'to-dr',
    queueName: 'orders.out',
    forwardingAddress: 'orders.in',
    staticConnectors: [],
    messagesAcknowledged: 12,
    messagesPendingAcknowledgement: 3,
    started: true,
    connected: true,
    useDuplicateDetection: true,
    highlyAvailable: false,
    nodesPresent: 1,
    nodesTotal: 2,
    perNode: [],
    ...over,
  };
}

function serve({
  diverts = [],
  bridges = [],
  count,
}: { diverts?: DivertView[]; bridges?: BridgeView[]; count?: number } = {}) {
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
      HttpResponse.json({
        id: 'c1',
        name: 'c1',
        description: null,
        topology: { nodes: [] },
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: AVAILABLE,
          messageIo: AVAILABLE,
          slowConsumerDetection: AVAILABLE,
        },
        health: { level: 'OK', reasons: [] },
        environmentId: null,
      }),
    ),
    http.get('*/api/v1/clusters/c1/diverts', () =>
      HttpResponse.json({ data: diverts, count: count ?? diverts.length, page: 1, pageSize: 200 }),
    ),
    http.get('*/api/v1/clusters/c1/bridges', () =>
      HttpResponse.json({ data: bridges, count: count ?? bridges.length, page: 1, pageSize: 200 }),
    ),
  );
}

/** What a navigate() call would do to the address it starts from. */
function lastSearch(prev: Record<string, unknown> = {}) {
  const update = navigate.mock.lastCall![0].search as (prev: Record<string, unknown>) => Record<string, unknown>;
  return update(prev);
}

beforeEach(() => {
  search = {};
  contributed = [{ id: 'builder', order: 10, title: 'Builder', Component: () => <p>builder body</p> }];
  navigate.mockReset();
});

describe('RoutingView diverts', () => {
  it('reads each divert as a direction, an effect and an owner, in words', async () => {
    serve({
      diverts: [
        divert({ name: 'capture-tap', owner: 'MESSAGE_CAPTURE', exclusive: true }),
        divert({ name: 'by-operator', owner: 'OPERATOR', brokerXml: '<divert name="by-operator"/>' }),
        divert({ name: 'legacy', owner: null, filter: "color='red'", nodesPresent: 1 }),
      ],
    });
    renderWithProviders(<RoutingView />);

    const grid = await screen.findByRole('grid', { name: 'Diverts' });
    expect(within(grid).getAllByLabelText('from orders to orders.audit')).toHaveLength(3);
    expect(within(grid).getByText('takes the message')).toBeInTheDocument();
    expect(within(grid).getAllByText('copies the message')).toHaveLength(2);
    expect(within(grid).getByText('message capture')).toBeInTheDocument();
    expect(within(grid).getByText('not recorded')).toBeInTheDocument();
    expect(within(grid).getByText("color='red'")).toBeInTheDocument();
    expect(within(grid).getByText('1/2')).toBeInTheDocument();
    expect(screen.getByText('1–3 of 3 diverts')).toBeInTheDocument();
  });

  it('opens the broker.xml that would make the configuration match for a divert Studio created', async () => {
    serve({ diverts: [divert({ name: 'by-operator', owner: 'OPERATOR', brokerXml: '<divert name="by-operator"/>' })] });
    const user = userEvent.setup();
    renderWithProviders(<RoutingView />);

    await user.click(await screen.findByRole('button', { name: 'Studio — not in broker.xml' }));

    const dialog = await screen.findByRole('dialog', { name: '"by-operator" is not in broker.xml' });
    expect(within(dialog).getByText(/will not appear in the broker\.xml/)).toBeInTheDocument();
    expect(within(dialog).getByText('<divert name="by-operator"/>')).toBeInTheDocument();
  });

  it('still explains the drift when Studio holds no broker.xml for the divert', async () => {
    serve({ diverts: [divert({ owner: 'OPERATOR', brokerXml: null })] });
    const user = userEvent.setup();
    renderWithProviders(<RoutingView />);

    await user.click(await screen.findByRole('button', { name: 'Studio — not in broker.xml' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/will not appear in the broker\.xml/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/Add this to broker.xml/)).not.toBeInTheDocument();
  });

  it('teaches what a divert is when there are none, and says a filter emptied the list when one is set', async () => {
    serve();
    const { unmount } = renderWithProviders(<RoutingView />);
    expect(await screen.findByText(/No diverts\. A divert copies/)).toBeInTheDocument();
    expect(screen.getByText('No diverts')).toBeInTheDocument();
    unmount();

    search = { q: 'zzz' };
    renderWithProviders(<RoutingView />);
    expect(
      await screen.findByText('No divert matches this filter. Clear it to see every divert on the cluster.'),
    ).toBeInTheDocument();
  });

  it('states why the diverts could not be read instead of listing none', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/diverts', () =>
        HttpResponse.json({ title: 'Cluster unreachable', detail: 'No node answered.' }, { status: 502 }),
      ),
    );
    renderWithProviders(<RoutingView />);

    expect(await screen.findByText('Cluster unreachable')).toBeInTheDocument();
    expect(screen.getByText('No node answered.')).toBeInTheDocument();
    expect(screen.queryByRole('grid', { name: 'Diverts' })).not.toBeInTheDocument();
  });

  it('puts the sort in the address and starts again at the first page', async () => {
    serve({ diverts: [divert()] });
    const user = userEvent.setup();
    renderWithProviders(<RoutingView />);

    await screen.findByRole('grid', { name: 'Diverts' });
    await user.click(within(screen.getByRole('columnheader', { name: /Name/ })).getByRole('button'));

    expect(lastSearch({ page: 3 })).toEqual({ page: undefined, sort: 'name' });
  });

  it('pages through a long list, saying where it is, and drops the page parameter on the first page', async () => {
    search = { page: 2 };
    serve({ diverts: [divert()], count: 450 });
    const user = userEvent.setup();
    renderWithProviders(<RoutingView />);

    expect(await screen.findByText('201–400 of 450 diverts')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(lastSearch()).toEqual({ page: 3 });
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(lastSearch()).toEqual({ page: undefined });
  });

  it('writes the typed filter into the address once typing pauses, and resets the page', async () => {
    serve({ diverts: [divert()] });
    const user = userEvent.setup();
    renderWithProviders(<RoutingView />);

    await user.type(await screen.findByRole('textbox', { name: 'Filter by address or name' }), 'orders');

    await waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(lastSearch({ page: 4, tab: 'bridges' })).toEqual({ page: undefined, tab: 'bridges', q: 'orders' });
  });

  it('offers to create a divert', async () => {
    serve({ diverts: [divert()] });
    renderWithProviders(<RoutingView />);

    expect(await screen.findByRole('button', { name: /Create divert/ })).toBeEnabled();
  });
});

describe('RoutingView bridges', () => {
  it('says whether each bridge is running, half-up or stopped, in words', async () => {
    search = { tab: 'bridges' };
    serve({
      bridges: [
        bridge({ name: 'up', started: true, connected: true }),
        bridge({ name: 'half', started: true, connected: false, queueName: null, forwardingAddress: null }),
        bridge({ name: 'down', started: false, connected: false }),
      ],
    });
    renderWithProviders(<RoutingView />);

    const grid = await screen.findByRole('grid', { name: 'Bridges' });
    expect(within(grid).getByText('running and connected')).toBeInTheDocument();
    expect(within(grid).getByText('started, not connected to its target')).toBeInTheDocument();
    expect(within(grid).getByText('not started')).toBeInTheDocument();
    // A bridge with nothing recorded is named as such rather than left blank.
    expect(within(grid).getByLabelText('from (unnamed queue) to (the target broker)')).toBeInTheDocument();
    expect(within(grid).getAllByLabelText('from orders.out to orders.in')).toHaveLength(2);
    expect(within(grid).getAllByText('12').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /Create divert/ })).not.toBeInTheDocument();
  });

  it('points to the Builder tab for declaring a bridge when the builder is there', async () => {
    search = { tab: 'bridges' };
    serve();
    renderWithProviders(<RoutingView />);

    expect(await screen.findByText(/Declare one on the Builder tab and apply it/)).toBeInTheDocument();
  });

  it('says the cluster configuration is not enabled when there is no builder to point to', async () => {
    search = { tab: 'bridges' };
    contributed = [];
    serve();
    renderWithProviders(<RoutingView />);

    expect(await screen.findByText(/which is not enabled on this Studio/)).toBeInTheDocument();
  });

  it('says a filter emptied the bridges and offers to clear it', async () => {
    search = { tab: 'bridges', q: 'zzz' };
    serve();
    renderWithProviders(<RoutingView />);

    expect(
      await screen.findByText('No bridge matches this filter. Clear it to see every bridge on the cluster.'),
    ).toBeInTheDocument();
  });
});

describe('RoutingView tabs', () => {
  it('lists Diverts, Bridges and each contributed tab, with the address deciding which is open', async () => {
    serve();
    renderWithProviders(<RoutingView />);

    expect(await screen.findByRole('tab', { name: 'Diverts', selected: true })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Bridges', selected: false })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Builder', selected: false })).toBeInTheDocument();
  });

  it('shows a contributed tab in place of the listing, handing it the cluster', async () => {
    search = { tab: 'builder' };
    contributed = [
      { id: 'builder', order: 10, title: 'Builder', Component: ({ clusterId }) => <p>builder of {clusterId}</p> },
    ];
    serve();
    renderWithProviders(<RoutingView />);

    expect(await screen.findByText('builder of c1')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Builder', selected: true })).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Filter by address or name' })).not.toBeInTheDocument();
  });

  it('falls back to Diverts for a tab nothing provides', async () => {
    search = { tab: 'gone' };
    serve();
    renderWithProviders(<RoutingView />);

    expect(await screen.findByRole('tab', { name: 'Diverts', selected: true })).toBeInTheDocument();
  });

  it('drops what the last tab kept in the address when the tab changes, and Diverts is the bare address', async () => {
    search = { tab: 'bridges', q: 'x' };
    serve();
    const user = userEvent.setup();
    renderWithProviders(<RoutingView />);

    await user.click(await screen.findByRole('tab', { name: 'Builder' }));
    expect(lastSearch({ q: 'x' })).toEqual({ tab: 'builder' });

    await user.click(screen.getByRole('tab', { name: 'Diverts' }));
    expect(lastSearch({ q: 'x' })).toEqual({ tab: undefined });
  });
});
