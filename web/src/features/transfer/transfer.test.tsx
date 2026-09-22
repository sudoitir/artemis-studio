import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { MessageSelection } from '../../kernel/slots.ts';
import type { Finding, OrphanView, TransferRunView } from './api.ts';

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function endpoint(id: string, name: string, over: Record<string, unknown> = {}) {
  return {
    id,
    name,
    artemisNodeId: id,
    jolokiaUrl: `http://${name}:8161/console/jolokia`,
    coreUrl: `tcp://${name}:61616`,
    haRole: 'PRIMARY',
    state: 'LIVE',
    active: true,
    replicaSync: null,
    version: '2.57.0',
    lastError: null,
    lastSeenAt: null,
    discovered: true,
    manualOverride: false,
    manageable: true,
    ...over,
  };
}

/** Two live primaries, plus a backup that must be listed and unchoosable. */
function topology(clusterId: string) {
  return {
    clusterId,
    nodes: [
      { artemisNodeId: 'n1', splitBrain: 'NONE', replicationBehind: false, endpoints: [endpoint('n1', 'node-a')] },
      { artemisNodeId: 'n2', splitBrain: 'NONE', replicationBehind: false, endpoints: [endpoint('n2', 'node-b')] },
      {
        artemisNodeId: 'n3',
        splitBrain: 'NONE',
        replicationBehind: false,
        endpoints: [endpoint('n3', 'node-b-backup', { haRole: 'BACKUP', active: false, state: 'BACKUP' })],
      },
    ],
  };
}

function clusterHandlers() {
  const detail = (id: string, name: string) => ({
    id,
    name,
    description: null,
    topology: topology(id),
    capabilities: {
      managementRead: AVAILABLE,
      managementWrite: AVAILABLE,
      notifications: AVAILABLE,
      messageIo: AVAILABLE,
      slowConsumerDetection: AVAILABLE,
    },
    health: { level: 'OK', reasons: [] },
    environmentId: null,
  });
  return [
    http.get('*/api/v1/clusters/c1', () => HttpResponse.json(detail('c1', 'primary'))),
    http.get('*/api/v1/clusters/c2', () => HttpResponse.json(detail('c2', 'dr-site'))),
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json([
        { id: 'c1', name: 'primary', nodeCount: 2, updatedAt: '2026-09-21T10:00:00Z', environmentId: null },
        { id: 'c2', name: 'dr-site', nodeCount: 2, updatedAt: '2026-09-21T10:00:00Z', environmentId: null },
      ]),
    ),
    http.get('*/api/v1/clusters/:id/queues', () => HttpResponse.json({ items: [], total: 0 })),
  ];
}

function meHandler(permissions: string[] = ['*']) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'admin',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

const END = (clusterId: string, nodeId: string, nodeName: string, queue: string) => ({
  clusterId,
  nodeId,
  nodeName,
  queue,
  address: queue,
});

function run(over: Partial<TransferRunView> = {}): TransferRunView {
  return {
    id: 'r1',
    mode: 'MOVE',
    state: 'PREVIEWED',
    source: END('c1', 'n1', 'node-a', 'orders'),
    target: END('c2', 'n1', 'node-a', 'orders'),
    sameNode: false,
    selection: { kind: 'ALL', ids: null, filter: null },
    t0: '2026-09-21T10:00:00Z',
    planHash: 'h1',
    username: 'admin',
    createdAt: '2026-09-21T10:00:00Z',
    expiresAt: '2026-09-21T10:10:00Z',
    startedAt: null,
    updatedAt: null,
    finishedAt: null,
    estimate: 1200,
    estimateBytes: 84_000_000,
    staged: 0,
    held: 0,
    delivered: 0,
    notTransferred: 0,
    expired: 0,
    returned: 0,
    bytes: 0,
    messagesPerSecond: null,
    stagingQueue: null,
    findings: [],
    notes: [],
    cap: 10_000,
    overCap: false,
    overrideCap: false,
    resumable: false,
    returnable: false,
    auditEventId: 7,
    targetAuditEventId: 8,
    lastError: null,
    errorSnippet: null,
    ...over,
  };
}

const finding = (kind: Finding['kind'], code: string, words: string, snippet?: string): Finding => ({
  kind,
  code,
  words,
  snippet: snippet ?? null,
});

const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1', runId: 'r1' }),
  useSearch: () => ({}),
  useNavigate: () => navigate,
  Link: ({ children, to, search }: { children: React.ReactNode; to: string; search?: Record<string, string> }) => (
    <a href={search ? `${to}?${new URLSearchParams(search)}` : to}>{children}</a>
  ),
}));

const { TransferActions } = await import('./TransferActions.tsx');
const { TransferRunView: RunView } = await import('./TransferRunView.tsx');
const { TransfersView } = await import('./TransfersView.tsx');

const ALL: MessageSelection = { kind: 'all' };

function Actions({ selection = ALL, total = 1200 }: { selection?: MessageSelection; total?: number | null }) {
  return (
    <TransferActions
      clusterId="c1"
      queueName="orders"
      node="n1"
      selection={selection}
      total={total}
      clear={() => {}}
    />
  );
}

/** Mantine renders its dropdown in a portal the modal marks aria-hidden, so options are queried hidden. */
const opt = { hidden: true } as const;

const problem = (status: number, type: string, title: string, detail: string) =>
  HttpResponse.json({ type: `https://artemis-studio.dev/problems/${type}`, title, status, detail }, { status });

function previewHandler(body: TransferRunView, bodies: unknown[] = []) {
  return http.post('*/api/v1/clusters/c1/transfers/preview', async ({ request }) => {
    bodies.push(await request.json());
    return HttpResponse.json(body, { status: 201 });
  });
}

async function openDialog(user: ReturnType<typeof userEvent.setup>, label = 'Transfer…') {
  await waitFor(() => expect(screen.getByRole('button', { name: label })).toBeEnabled());
  await user.click(screen.getByRole('button', { name: label }));
  return screen.findByRole('dialog');
}

describe('TransferActions', () => {
  it('keeps a transfer the operator may not run visible, disabled, with a reachable reason', async () => {
    server.use(meHandler(['cluster:read']), ...clusterHandlers());
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Transfer…' })).toBeDisabled());
    await user.click(screen.getByRole('button', { name: 'Why transferring these messages is unavailable' }));
    expect(await screen.findByText(/Browse messages/)).toBeInTheDocument();
  });

  it('says an empty queue is why there is nothing to transfer', async () => {
    server.use(meHandler(), ...clusterHandlers());
    renderWithProviders(<Actions total={0} />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Transfer…' })).toBeDisabled());
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Why transferring these messages is unavailable' }));
    expect(await screen.findByText(/no messages to transfer/)).toBeInTheDocument();
  });
});

describe('TransferDialog', () => {
  it('previews the whole queue with every field the server requires', async () => {
    const bodies: unknown[] = [];
    server.use(meHandler(), ...clusterHandlers(), previewHandler(run(), bodies));
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
    await user.click(await screen.findByRole('option', { name: 'node-b', ...opt }));
    await user.click(within(dialog).getByRole('button', { name: 'Preview' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({
      mode: 'MOVE',
      sourceQueue: 'orders',
      sourceNodeId: 'n1',
      selection: { kind: 'ALL', ids: null, filter: null },
      targetClusterId: 'c1',
      targetNodeId: 'n2',
      targetQueue: 'orders',
      targetAddress: null,
    });
  });

  it('sends the picked ids when rows are selected', async () => {
    const bodies: unknown[] = [];
    server.use(meHandler(), ...clusterHandlers(), previewHandler(run({ estimate: 2 }), bodies));
    const user = userEvent.setup();
    renderWithProviders(<Actions selection={{ kind: 'ids', ids: [11, 12] }} total={2} />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
    await user.click(await screen.findByRole('option', { name: 'node-b', ...opt }));
    await user.click(within(dialog).getByRole('button', { name: 'Preview' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ selection: { kind: 'IDS', ids: [11, 12], filter: null } });
  });

  it('lists a node that cannot take messages, disabled, saying why', async () => {
    server.use(meHandler(), ...clusterHandlers());
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
    expect(await screen.findByRole('option', { name: /node-a: the source node/, ...opt })).toHaveAttribute(
      'data-combobox-disabled',
    );
    expect(screen.getByRole('option', { name: /node-b-backup: a backup/, ...opt })).toHaveAttribute('data-combobox-disabled');
  });

  it('lists a cluster the operator may not send to, disabled, saying why', async () => {
    server.use(meHandler(['message:move', 'message:read', 'cluster:read']), ...clusterHandlers());
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('combobox', { name: 'Target cluster' }));
    expect(await screen.findByRole('option', { name: /dr-site: you do not have the "Send messages" permission/, ...opt })).toHaveAttribute(
      'data-combobox-disabled',
    );
  });

  it('will not preview without a target node, and puts focus on the field', async () => {
    server.use(meHandler(), ...clusterHandlers());
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('button', { name: 'Preview' }));

    expect(await within(dialog).findByText('Choose the node the messages go to.')).toBeInTheDocument();
    expect(within(dialog).getByRole('combobox', { name: 'Target node' })).toHaveFocus();
  });

  it('states the blast radius and refuses a transfer the target cannot accept', async () => {
    server.use(
      meHandler(),
      ...clusterHandlers(),
      previewHandler(
        run({
          findings: [
            finding(
              'REFUSE',
              'address-full-policy-drop',
              'orders on node-b drops messages when its address is full, so a transfer could lose them silently.',
              '<address-setting match="orders"><address-full-policy>PAGE</address-full-policy></address-setting>',
            ),
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
    await user.click(await screen.findByRole('option', { name: 'node-b', ...opt }));
    await user.click(within(dialog).getByRole('button', { name: 'Preview' }));

    expect(await within(dialog).findByText(/Move 1,200 messages \(84 MB\)/)).toBeInTheDocument();
    expect(within(dialog).getByText(/The target cannot accept this transfer/)).toBeInTheDocument();
    expect(within(dialog).getByText(/drops messages when its address is full/)).toBeInTheDocument();
    expect(within(dialog).getByText(/<address-full-policy>PAGE<\/address-full-policy>/)).toBeInTheDocument();
    // Nothing to arm: a refused transfer offers no confirmation at all.
    expect(within(dialog).queryByRole('button', { name: /^Move 1,200 messages$/ })).not.toBeInTheDocument();
  });

  it('holds a warned transfer until every warning is acknowledged, then arms it by typed confirmation', async () => {
    const executed: unknown[] = [];
    server.use(
      meHandler(),
      ...clusterHandlers(),
      previewHandler(
        run({
          findings: [
            finding('WARN', 'id-cache-off', 'node-b does not persist its duplicate-id cache.'),
            finding('UNKNOWN', 'disk-unknown', 'node-b did not answer for its disk use, so it was not checked.'),
          ],
        }),
      ),
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/execute', async ({ request }) => {
        executed.push(await request.json());
        return HttpResponse.json(run({ state: 'RUNNING', startedAt: '2026-09-21T10:01:00Z' }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
    await user.click(await screen.findByRole('option', { name: 'node-b', ...opt }));
    await user.click(within(dialog).getByRole('button', { name: 'Preview' }));

    // The unknown is stated as unknown, never as a pass.
    expect(await within(dialog).findByText(/Could not be checked, so not counted as passing/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Acknowledge the warning above to run this/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Move 1,200 messages' })).toBeDisabled();

    await user.click(within(dialog).getByRole('checkbox', { name: /does not persist its duplicate-id cache/ }));
    const confirm = within(dialog).getByRole('button', { name: 'Move 1,200 messages' });
    expect(confirm).toBeDisabled();

    // A move is armed only by typing the source queue's name.
    await user.type(within(dialog).getByRole('textbox', { name: /Type the source queue's name/ }), 'orders');
    await user.click(within(dialog).getByRole('button', { name: 'Move 1,200 messages' }));

    await waitFor(() => expect(executed).toHaveLength(1));
    expect(executed[0]).toEqual({
      planHash: 'h1',
      override: false,
      acknowledged: ['id-cache-off'],
      confirmQueue: 'orders',
    });
    await waitFor(() => expect(navigate).toHaveBeenCalledWith({ to: '/clusters/c1/transfers/r1' }));
  });

  it('says nothing ran when the plan changed, and offers a fresh preview', async () => {
    server.use(
      meHandler(),
      ...clusterHandlers(),
      previewHandler(run({ mode: 'COPY' })),
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/execute', () =>
        problem(
          409,
          'transfer-plan-mismatch',
          'Conflict',
          'This is not the plan that was previewed. Preview again and confirm what it shows.',
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
    await user.click(await screen.findByRole('option', { name: 'node-b', ...opt }));
    await user.click(within(dialog).getByRole('button', { name: 'Preview' }));
    // A copy does not take the source's messages, so it needs no typed confirmation.
    await user.click(await within(dialog).findByRole('button', { name: 'Copy 1,200 messages' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('This is not the plan that was previewed.');
    expect(alert).toHaveTextContent('Nothing was run.');
    expect(within(alert).getByRole('button', { name: 'Preview again' })).toBeInTheDocument();
  });

  it('states a refused preview and leaves the destination to change', async () => {
    server.use(
      meHandler(),
      ...clusterHandlers(),
      http.post('*/api/v1/clusters/c1/transfers/preview', () =>
        problem(404, 'cluster-not-found', 'Not found', 'No cluster with that id, or you may not see it.'),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('combobox', { name: 'Target node' }));
    await user.click(await screen.findByRole('option', { name: 'node-b', ...opt }));
    await user.click(within(dialog).getByRole('button', { name: 'Preview' }));

    const alert = await within(dialog).findByRole('alert');
    expect(alert).toHaveTextContent('No cluster with that id');
    expect(alert).toHaveTextContent('Nothing was moved.');
  });

  it('takes focus, closes on Escape and hands focus back to the button that opened it', async () => {
    server.use(meHandler(), ...clusterHandlers());
    const user = userEvent.setup();
    renderWithProviders(<Actions />);

    const trigger = await screen.findByRole('button', { name: 'Transfer…' });
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);

    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});

describe('TransferRunView', () => {
  const show = (over: Partial<TransferRunView>) => {
    server.use(
      meHandler(),
      ...clusterHandlers(),
      http.get('*/api/v1/clusters/c1/transfers/runs/r1', () => HttpResponse.json(run(over))),
    );
    renderWithProviders(<RunView />);
  };

  it('announces a running transfer, with what is held and how fast it is going', async () => {
    show({ state: 'RUNNING', staged: 400, held: 120, delivered: 280, messagesPerSecond: 140 });

    const status = await screen.findByRole('status', { name: 'Transfer state' });
    expect(status).toHaveTextContent('Running.');
    expect(status).toHaveTextContent('120 messages held in staging on node-a.');
    expect(screen.getByText(/140 messages a second/)).toBeInTheDocument();
    expect(screen.getByText(/about 7 seconds left/)).toBeInTheDocument();
  });

  it('says a partial run is partial and why some messages were not taken', async () => {
    show({ state: 'PARTIAL', delivered: 1190, notTransferred: 10, finishedAt: '2026-09-21T10:05:00Z' });

    const status = await screen.findByRole('status', { name: 'Transfer state' });
    expect(status).toHaveTextContent('Partial: 10 selected messages could not be taken');
    expect(screen.getByText('Not transferred')).toBeInTheDocument();
  });

  it('says a failed run lost nothing, and offers resume and return', async () => {
    show({
      state: 'FAILED',
      held: 900,
      delivered: 300,
      resumable: true,
      returnable: true,
      lastError: 'node-b refused the batch: its address is full.',
    });

    const status = await screen.findByRole('status', { name: 'Transfer state' });
    expect(status).toHaveTextContent('Nothing held is lost');
    expect(screen.getAllByText('node-b refused the batch: its address is full.').length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Resume' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Return to source…' })).toBeEnabled();
  });

  it('returns the held messages only after the source queue name is typed', async () => {
    let returned = 0;
    server.use(
      meHandler(),
      ...clusterHandlers(),
      http.get('*/api/v1/clusters/c1/transfers/runs/r1', () =>
        HttpResponse.json(run({ state: 'STOPPED', held: 900, delivered: 300, resumable: true, returnable: true })),
      ),
      http.post('*/api/v1/clusters/c1/transfers/runs/r1/return', () => {
        returned += 1;
        return HttpResponse.json(run({ state: 'RETURNED', returned: 900 }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RunView />);

    await user.click(await screen.findByRole('button', { name: 'Return to source…' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/900 messages now, goes back on orders/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Return 900 messages' })).toBeDisabled();

    await user.type(within(dialog).getByRole('textbox', { name: /Type the source queue's name/ }), 'orders');
    await user.click(within(dialog).getByRole('button', { name: 'Return 900 messages' }));
    await waitFor(() => expect(returned).toBe(1));
  });

  it('says a succeeded run succeeded, how fast it went, and offers no command', async () => {
    show({
      state: 'SUCCEEDED',
      delivered: 1200,
      staged: 1200,
      startedAt: '2026-09-21T10:00:00Z',
      finishedAt: '2026-09-21T10:00:30Z',
    });

    const status = await screen.findByRole('status', { name: 'Transfer state' });
    expect(status).toHaveTextContent('Succeeded: every selected message was moved.');
    // A finished run states its own pace; "no rate yet" would read as a stall.
    expect(screen.getByText('1,200 messages moved in 30 seconds, 40 a second.')).toBeInTheDocument();
    expect(screen.queryByText(/No rate yet/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resume' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument();
  });
});

describe('TransfersView', () => {
  it('teaches what a transfer is when there are none', async () => {
    server.use(
      meHandler(),
      ...clusterHandlers(),
      http.get('*/api/v1/clusters/c1/transfers/runs', () => HttpResponse.json([])),
      http.get('*/api/v1/clusters/c1/transfers/orphans', () => HttpResponse.json([])),
    );
    renderWithProviders(<TransfersView />);

    expect(await screen.findByText('No transfers yet')).toBeInTheDocument();
    expect(screen.getByText(/checks the target can accept them/)).toBeInTheDocument();
  });

  it('lists staging with no run, and returns it to a named queue', async () => {
    const orphan: OrphanView = { nodeId: 'n1', nodeName: 'node-a', stagingQueue: 'studio.transfer.r9', depth: 42 };
    const bodies: unknown[] = [];
    server.use(
      meHandler(),
      ...clusterHandlers(),
      http.get('*/api/v1/clusters/c1/transfers/runs', () => HttpResponse.json([run({ state: 'SUCCEEDED' })])),
      http.get('*/api/v1/clusters/c1/transfers/orphans', () => HttpResponse.json([orphan])),
      http.post('*/api/v1/clusters/c1/transfers/orphans/return', async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({ stagingQueue: 'studio.transfer.r9', returned: 42, remaining: 0, removed: true });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<TransfersView />);

    expect(await screen.findByText(/studio\.transfer\.r9 on node-a — 42 messages/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Return to…' }));

    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox', { name: /Return them to/ }), 'orders');
    await user.type(within(dialog).getByRole('textbox', { name: /Type the staging queue's name/ }), 'studio.transfer.r9');
    await user.click(within(dialog).getByRole('button', { name: 'Return the messages' }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({ nodeId: 'n1', stagingQueue: 'studio.transfer.r9', targetQueue: 'orders' });
    expect(await screen.findByText(/42 messages returned from studio\.transfer\.r9\./)).toBeInTheDocument();
  });
});
