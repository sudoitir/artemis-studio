import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1', queueName: 'PHASE3.SRC' }),
  useSearch: () => ({}),
  useNavigate: () => () => {},
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

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
