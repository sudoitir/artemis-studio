import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

// The address is state here, as it is in the app: the open message lives in it (`?message=`).
let searchState: Record<string, unknown> = {};
const searchListeners = new Set<() => void>();
afterEach(() => {
  searchState = {};
});

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const { useSyncExternalStore } = await import('react');
  return {
    ...(await importOriginal<typeof import('@tanstack/react-router')>()),
    useParams: () => ({ clusterId: 'c1', queueName: 'PHASE3.SRC' }),
    useSearch: () =>
      useSyncExternalStore(
        (listener) => {
          searchListeners.add(listener);
          return () => searchListeners.delete(listener);
        },
        () => searchState,
      ),
    useNavigate: () => (options: { search?: unknown }) => {
      if (typeof options.search === 'function') searchState = options.search(searchState);
      searchListeners.forEach((listener) => listener());
    },
    Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
  };
});

const { MessagesView } = await import('./MessagesView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function endpoint(id: string, name: string) {
  return {
    id,
    name,
    artemisNodeId: 'NID',
    jolokiaUrl: `http://${name}:8161/jolokia`,
    coreUrl: null,
    haRole: 'PRIMARY',
    state: 'STARTED',
    active: name === 'primary',
    replicaSync: null,
    version: '2.44.0',
    lastError: null,
    lastSeenAt: null,
    discovered: false,
    manualOverride: false,
    manageable: true,
  };
}

function mockCluster(endpoints: ReturnType<typeof endpoint>[]) {
  server.use(
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod',
        description: null,
        topology: {
          clusterId: 'c1',
          nodes: [{ artemisNodeId: 'NID', splitBrain: 'NONE', replicationBehind: false, endpoints }],
        },
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: { status: 'UNKNOWN', reason: 'phase 4', brokerXmlSnippet: null },
          messageIo: AVAILABLE,
        },
        health: {
          clusterId: 'c1',
          level: 'OK',
          liveEndpointNames: [],
          splitBrain: 'NONE',
          replicationBehind: false,
          notes: [],
        },
      }),
    ),
    http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
      HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 200, node: endpoints[0].id }),
    ),
  );
}

describe('MessagesView', () => {
  it('keeps the page current on its own, without an operator pressing refresh', async () => {
    // A queue changes under the operator: a browse that only ever loads once
    // shows an arrangement of the queue that stopped being true when it drew.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let browses = 0;
    try {
      mockCluster([endpoint('n1', 'primary')]);
      server.use(
        http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () => {
          browses += 1;
          return HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 200, node: 'n1' });
        }),
      );
      const { unmount } = renderWithProviders(<MessagesView />);
      await vi.waitFor(() => expect(browses).toBe(1));

      await vi.advanceTimersByTimeAsync(11_000);
      await vi.waitFor(() => expect(browses).toBeGreaterThan(1));
      unmount();
    } finally {
      vi.useRealTimers();
    }
  });

  it('hides the node selector when the queue is served by a single endpoint', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('PHASE3.SRC')).toBeInTheDocument();
    expect(screen.queryAllByLabelText('Node to browse')).toHaveLength(0);
  });

  it('shows the node selector when there is more than one manageable endpoint', async () => {
    mockCluster([endpoint('n1', 'primary'), endpoint('n2', 'backup')]);
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText('PHASE3.SRC')).toBeInTheDocument();
    await vi.waitFor(() =>
      expect(screen.getAllByLabelText('Node to browse').length).toBeGreaterThan(0),
    );
  });

  it('states an unavailable total instead of showing zero', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json({
          data: [],
          count: null,
          countUnavailable: 'the broker did not answer the count in time',
          page: 1,
          pageSize: 200,
          node: 'n1',
        }),
      ),
    );
    renderWithProviders(<MessagesView />);

    expect(await screen.findByText(/total unavailable — the broker did not answer the count in time/)).toBeInTheDocument();
    expect(screen.getByText(/page 1 · total unavailable/)).toBeInTheDocument();
    expect(screen.queryByText(/^0 messages/)).not.toBeInTheDocument();
  });

  it('renders every message of a populated page and opens the one clicked', async () => {
    const summary = (messageId: number, body: string, truncated = false) => ({
      messageId,
      type: 3,
      durable: true,
      priority: 4,
      timestamp: 1789847475826 + messageId,
      expiration: 0,
      size: body.length,
      groupId: null,
      correlationId: null,
      bodyPreview: body,
      bodyTruncated: truncated,
      propertyCount: 1,
      redactions: [],
    });
    mockCluster([endpoint('n1', 'primary'), endpoint('n2', 'secondary')]);
    server.use(
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json({
          data: [summary(101, 'order A-1'), summary(102, 'order A-2', true), summary(103, 'order A-3')],
          count: 3,
          countUnavailable: null,
          page: 1,
          pageSize: 200,
          node: 'n2',
          transport: 'CORE',
        }),
      ),
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages/102', () =>
        HttpResponse.json({
          ...summary(102, 'order A-2', true),
          userId: null,
          body: 'order A-2',
          bodyEncoding: 'TEXT',
          contentType: null,
          observedLimitBytes: null,
          transport: 'CORE',
          node: 'n2',
          stringProperties: { orderId: 'A-2' },
          intProperties: {},
          longProperties: {},
          doubleProperties: {},
          booleanProperties: {},
          withheld: [],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    // The count and the node it was read from — the second endpoint, not the first listed.
    expect(await screen.findByText(/3 messages · read from secondary/)).toBeInTheDocument();
    const rows = await screen.findAllByRole('row');
    // Row 0 is the header.
    expect(rows).toHaveLength(4);
    expect(within(rows[1]).getByText('order A-1')).toBeInTheDocument();
    expect(within(rows[2]).getByText('order A-2')).toBeInTheDocument();
    expect(within(rows[2]).getByText('truncated')).toBeInTheDocument();
    expect(within(rows[3]).getByText('order A-3')).toBeInTheDocument();
    expect(screen.queryByText('No messages match')).not.toBeInTheDocument();

    await user.click(within(rows[2]).getByText('order A-2'));

    const drawer = await screen.findByRole('dialog', { name: 'Message 102' });
    expect(await within(drawer).findByText('A-2')).toBeInTheDocument();
  });
});


describe('the purge estimate', () => {
  it('states an estimate that could not be taken, and still lets the purge be armed', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.delete('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json(
          { title: 'The broker did not answer', detail: 'The node timed out after 5s.' },
          { status: 504 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    await user.click(await screen.findByRole('button', { name: 'Purge queue' }));
    const dialog = await screen.findByRole('dialog');

    // An absent number reads as zero — the failure is stated instead.
    expect(await within(dialog).findByText(/The node timed out after 5s\./)).toBeInTheDocument();
    expect(within(dialog).queryByText(/Estimating current depth/)).not.toBeInTheDocument();

    // And the confirmation is not left disabled with no reason given.
    await user.type(within(dialog).getByRole('textbox'), 'PHASE3.SRC');
    await vi.waitFor(() =>
      expect(within(dialog).getByRole('button', { name: 'Purge queue' })).toBeEnabled(),
    );
  });
});

describe('the purge and the bulk safety cap', () => {
  it('does not override the cap when the depth is unknown', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    const urls: string[] = [];
    server.use(
      http.delete('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', ({ request }) => {
        const url = new URL(request.url);
        urls.push(url.search);
        if (url.searchParams.get('dryRun') === 'true') {
          return HttpResponse.json(
            { title: 'The broker did not answer', detail: 'The node timed out after 5s.' },
            { status: 504 },
          );
        }
        return HttpResponse.json({ affectedCount: 4, dryRun: false, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    await user.click(await screen.findByRole('button', { name: 'Purge queue' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(/The node timed out after 5s\./)).toBeInTheDocument();

    await user.type(within(dialog).getByRole('textbox'), 'PHASE3.SRC');
    await user.click(within(dialog).getByRole('button', { name: 'Purge queue' }));

    await vi.waitFor(() => expect(urls).toHaveLength(2));
    // The server's cap is the only guard left when Studio cannot state a blast radius.
    expect(urls.at(-1)).not.toContain('override=true');
  });

  it('states the cap it is about to override, and overrides it only then', async () => {
    mockCluster([endpoint('n1', 'primary')]);
    const urls: string[] = [];
    server.use(
      http.delete('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', ({ request }) => {
        const url = new URL(request.url);
        urls.push(url.search);
        if (url.searchParams.get('dryRun') === 'true') {
          return HttpResponse.json({ affectedCount: 5000, cap: 1000, overCap: true, node: 'n1' });
        }
        return HttpResponse.json({ affectedCount: 5000, dryRun: false, node: 'n1' });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    await user.click(await screen.findByRole('button', { name: 'Purge queue' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText(/over the cap of 1,000/)).toBeInTheDocument();

    await user.type(within(dialog).getByRole('textbox'), 'PHASE3.SRC');
    await user.click(within(dialog).getByRole('button', { name: 'Purge anyway, over the cap' }));

    await vi.waitFor(() => expect(urls).toHaveLength(2));
    expect(urls.at(-1)).toContain('override=true');
  });
});

describe('one message, from its row (ADR-0107)', () => {
  it('deletes a single message only once its id is typed, and states the outcome', async () => {
    const summary = {
      messageId: 205,
      type: 3,
      durable: true,
      priority: 4,
      timestamp: 1789847475826,
      expiration: 0,
      size: 9,
      groupId: null,
      correlationId: null,
      bodyPreview: 'order B-5',
      bodyTruncated: false,
      propertyCount: 0,
      redactions: [],
    };
    const bodies: unknown[] = [];
    mockCluster([endpoint('n1', 'primary')]);
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'op',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
      http.get('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages', () =>
        HttpResponse.json({
          data: [summary],
          count: 1,
          countUnavailable: null,
          page: 1,
          pageSize: 200,
          node: 'n1',
          transport: 'CORE',
        }),
      ),
      http.post('*/api/v1/clusters/c1/queues/PHASE3.SRC/messages/actions/delete', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({ affectedCount: 1 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<MessagesView />);

    await user.click(await screen.findByRole('button', { name: 'Actions for message 205' }));
    const menu = await screen.findByRole('menu', { name: 'Actions for message 205' });
    await user.click(within(menu).getByRole('menuitem', { name: /^Delete…/ }));

    const dialog = await screen.findByRole('dialog', { name: 'Delete message 205' });
    const confirm = within(dialog).getByRole('button', { name: 'Delete this message' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByRole('textbox', { name: /type "205" to confirm/i }), '205');
    await user.click(confirm);

    expect(await within(dialog).findByText('Done: the message was deleted.')).toBeInTheDocument();
    expect(bodies).toEqual([{ messageIds: [205] }]);
  });
});
