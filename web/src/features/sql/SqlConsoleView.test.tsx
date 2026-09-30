import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { EventSourceStub, server } from '../../test/setup.ts';

const search = { current: {} as { q?: string; live?: boolean } };

const navigate = vi.fn();

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search.current,
  useNavigate: () => navigate,
}));

const { SqlConsoleView } = await import('./SqlConsoleView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function target(nodeId: string, nodeName: string) {
  return {
    nodeId,
    nodeName,
    queueName: 'ORDER.IN',
    address: 'ORDER.IN',
    messageCount: 1200,
  };
}

function plan(overrides: Record<string, unknown> = {}) {
  return {
    source: 'BROKER',
    targets: [target('n1', 'primary')],
    selector: 'JMSPriority > 4',
    requiresScan: false,
    pushedDown: ['priority > 4'],
    scanned: [],
    estimatedMessagesExamined: 0,
    effectiveLimit: 100,
    notices: [],
    ...overrides,
  };
}

function answered(overrides: Record<string, unknown> = {}) {
  return {
    nodeId: 'n1',
    nodeName: 'primary',
    queueName: 'ORDER.IN',
    status: 'ANSWERED',
    examined: 500,
    matched: 0,
    servedBy: 'JOLOKIA',
    detail: null,
    ...overrides,
  };
}

function mockCluster(over: { permissions?: string[]; messageIo?: object; queues?: object[] } = {}) {
  server.use(
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod',
        description: null,
        topology: { clusterId: 'c1', nodes: [] },
        capabilities: {
          managementRead: AVAILABLE,
          managementWrite: AVAILABLE,
          notifications: {
            status: 'UNKNOWN',
            reason: 'n/a',
            brokerXmlSnippet: null,
          },
          messageIo: over.messageIo ?? AVAILABLE,
          slowConsumerDetection: AVAILABLE,
          versionGates: [],
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
    http.get('*/api/v1/clusters/c1/queues', () =>
      HttpResponse.json({ data: over.queues ?? [], count: (over.queues ?? []).length, page: 1, pageSize: 500 }),
    ),
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        username: 'op',
        displayName: 'Op',
        provider: 'LOCAL',
        mustChangePassword: false,
        grants: [
          {
            scopeType: 'GLOBAL',
            scopeId: null,
            roleName: 'admin',
            permissions: over.permissions ?? ['*'],
          },
        ],
      }),
    ),
  );
}

function mockPlan(body: Record<string, unknown> = plan()) {
  server.use(
    http.post('*/api/v1/clusters/c1/sql/plan', () => HttpResponse.json(body)),
    // Execution is POST-then-stream (ADR-0064): the text is posted and the stream is
    // opened by reference, so the query never appears in a URL.
    http.post('*/api/v1/clusters/c1/sql/query', () =>
      HttpResponse.json({ queryId: 'q-1', expiresAt: new Date().toISOString() }),
    ),
  );
}

/** Click Run and wait for the query stream to be opened. */
async function run(user: ReturnType<typeof userEvent.setup>, name = 'Run') {
  await user.click(await screen.findByRole('button', { name }));
  await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(1));
}

/** Deliver one frame from the server, inside act so React sees the state change. */
function emit(type: string, data: unknown) {
  act(() => EventSourceStub.emit(type, data));
}

describe('SqlConsoleView', () => {
  it('states that a query is pushed down and costs no scan', async () => {
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByText(/no scan — the broker filters/i)).toBeInTheDocument();
    expect(screen.getByText(/pushed down:/i)).toBeInTheDocument();
  });

  it('states the estimate when a predicate forces a scan, rather than omitting it', async () => {
    mockCluster();
    mockPlan(
      plan({
        requiresScan: true,
        pushedDown: [],
        scanned: ["body LIKE '%4471%'"],
        estimatedMessagesExamined: 12400,
      }),
    );
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByText(/scan — examines about 12,400 messages/i)).toBeInTheDocument();
    expect(screen.getByText(/scanned by studio:/i)).toBeInTheDocument();
  });

  it('reports a rejected query with the offending token instead of a generic failure', async () => {
    mockCluster();
    server.use(
      http.post('*/api/v1/clusters/c1/sql/plan', () =>
        HttpResponse.json(
          {
            title: 'That is not the console’s dialect',
            detail: 'JOIN is not part of this dialect.',
            offending: 'JOIN',
            suggestion: null,
          },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByText(/JOIN is not part of this dialect/i)).toBeInTheDocument();
    expect(screen.getByText(/the problem is at/i)).toBeInTheDocument();
  });

  it('renders a partial result as a per-node outcome, not as an empty table', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('done', {
      nodes: [
        answered(),
        answered({
          nodeId: 'n2',
          nodeName: 'backup',
          status: 'FAILED',
          examined: 0,
          servedBy: null,
          detail: 'connection refused',
        }),
      ],
      boundsReached: [{ kind: 'SCAN_CAP', value: 50000 }],
      notices: [],
      partial: true,
      plan: plan(),
    });

    // Three on purpose: the aria-live region, the meta bar's headline, and the
    // per-node summary's verdict. All three say the same thing rather than one of
    // them softening it.
    expect(await screen.findAllByText(/incomplete, this is a prefix of the answer/i)).toHaveLength(3);
    expect(screen.getByText('connection refused')).toBeInTheDocument();

    // Every bound is still stated in full — behind a disclosure rather than in a
    // full-width alert that pushes the rows off screen (7.4).
    await user.click(await screen.findByRole('button', { name: /what this means/i }));
    expect(screen.getByText(/the scan cap/i)).toBeInTheDocument();
    // An unanswered node is not an empty result, and must not be presented as one.
    expect(screen.getByText(/not because there was nothing/i)).toBeInTheDocument();
  });

  it('renders every row a multi-node broker result streams, attributed to its node', async () => {
    const twoNodes = plan({
      targets: [target('n1', 'primary'), target('n2', 'secondary')],
    });
    mockCluster();
    mockPlan(twoNodes);
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    const row = (nodeId: string, nodeName: string, messageId: number, body: string) => ({
      nodeId,
      nodeName,
      queueName: 'ORDER.IN',
      address: 'ORDER.IN',
      messageId,
      messageType: 3,
      durable: true,
      priority: 4,
      timestamp: 1757000000000 + messageId,
      expiration: 0,
      size: body.length,
      body,
      bodyTruncated: false,
      properties: {},
      source: 'BROKER',
      observedAt: null,
      lastSeenAt: null,
    });
    emit('row', row('n1', 'primary', 11, 'order 4471'));
    emit('row', row('n1', 'primary', 12, 'order 4472'));
    emit('row', row('n2', 'secondary', 21, 'order 5501'));
    emit('done', {
      nodes: [
        answered({ examined: 2, matched: 2 }),
        answered({ nodeId: 'n2', nodeName: 'secondary', examined: 1, matched: 1 }),
      ],
      boundsReached: [],
      notices: [],
      partial: false,
      plan: twoNodes,
    });

    await vi.waitFor(() => expect(screen.getAllByText(/order 4471|order 4472|order 5501/)).toHaveLength(3));
    const dataRows = screen.getAllByRole('row').filter((r) => /order \d{4}/.test(r.textContent ?? ''));
    expect(dataRows).toHaveLength(3);
    expect(
      within(dataRows.find((r) => r.textContent?.includes('order 5501'))!).getByText('secondary'),
    ).toBeInTheDocument();
    expect(dataRows.filter((r) => r.textContent?.includes('primary'))).toHaveLength(2);
  });

  it('distinguishes no-queue-matched from an empty queue', async () => {
    mockCluster();
    mockPlan(plan({ targets: [] }));
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('done', {
      nodes: [],
      boundsReached: [],
      notices: [{ kind: 'NO_QUEUE_MATCHED', detail: null }],
      partial: false,
      plan: plan({ targets: [] }),
    });

    expect(await screen.findByText('No queue matched')).toBeInTheDocument();
  });

  it('refuses an over-budget query with its estimate and how to narrow it', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    // The refusal is a frame, not an HTTP status: an EventSource cannot read the
    // body of a non-200, and a refusal without its estimate is not actionable.
    emit('failed', {
      status: 422,
      title: 'Query refused before it was started',
      detail: 'This query is too expensive to run.',
      estimate: 900000,
      ceiling: 250000,
      hint: 'Add a header predicate so the broker filters first.',
    });

    expect(await screen.findByText(/900,000 messages, against a ceiling of 250,000/i)).toBeInTheDocument();
    expect(screen.getByText(/add a header predicate/i)).toBeInTheDocument();
  });

  it('states the sampled-tail limitation while tailing, with no way to dismiss it', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', {
      nodes: [answered()],
      boundsReached: [],
      notices: [],
      partial: false,
      plan: plan(),
    });
    emit('tail', {
      enqueued: 40,
      shown: 12,
      polls: 3,
      lastPollAt: '2026-09-07T10:00:00Z',
      everyMessageMatches: true,
    });

    const notice = await screen.findByText(/is never seen/i);
    expect(notice).toBeInTheDocument();
    // Non-dismissable: the whole alert offers no close control. Only "stop
    // tailing" removes it, and that ends the tail rather than hiding its caveat.
    const alert = notice.closest('[role="alert"], .mantine-Alert-root') as HTMLElement;
    expect(within(alert).queryByRole('button', { name: /close/i })).not.toBeInTheDocument();
    // The observed gap is reported as a figure, not implied.
    expect(screen.getByText(/28 passed through between reads/i)).toBeInTheDocument();
  });

  it('stops the tail when the operator stops it, closing the stream', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', {
      nodes: [answered()],
      boundsReached: [],
      notices: [],
      partial: false,
      plan: plan(),
    });

    await user.click(await screen.findByRole('button', { name: /stop tailing/i }));

    // Closing the stream is what stops the poller: the server's sink reports
    // itself cancelled and issues no further broker read.
    await vi.waitFor(() => expect(EventSourceStub.instances[0].readyState).toBe(2));
    expect(screen.queryByText(/is never seen/i)).not.toBeInTheDocument();
  });

  it('does not silently re-run a query whose stream dropped', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    act(() => EventSourceStub.instances[0].onerror?.());

    // Twice on purpose: the aria-live announcement and the alert's own title.
    expect(await screen.findAllByText(/connection to this query was lost/i)).toHaveLength(2);
    expect(screen.getByRole('button', { name: /run it again/i })).toBeInTheDocument();
    // One stream, not two: reconnecting would fan out across brokers a second
    // time and write a second audit record for one intent.
    expect(EventSourceStub.instances).toHaveLength(1);
  });

  it('offers Verify on an indexed row, and never reports an unsettled read as gone', async () => {
    mockCluster();
    mockPlan(plan({ source: 'INDEX' }));
    server.use(
      http.post('*/api/v1/clusters/c1/sql/verify', () =>
        HttpResponse.json({
          presence: 'UNKNOWN',
          detail: 'More messages share this message’s enqueue time than one read returns.',
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('row', {
      nodeId: 'n1',
      nodeName: 'primary',
      queueName: 'ORDER.IN',
      address: 'ORDER.IN',
      messageId: 42,
      messageType: 3,
      durable: true,
      priority: 4,
      timestamp: 1757000000000,
      expiration: 0,
      size: 120,
      body: '{"orderId":"4471"}',
      bodyTruncated: false,
      properties: {},
      source: 'INDEX',
      observedAt: '2026-09-07T09:00:00Z',
      lastSeenAt: '2026-09-07T09:00:05Z',
    });
    emit('done', {
      nodes: [answered({ matched: 1 })],
      boundsReached: [],
      notices: [],
      partial: false,
      plan: plan({ source: 'INDEX' }),
    });

    // The result says where it came from once, in the meta bar, not only in a
    // per-row badge — and a sampled index row is not called a captured one.
    expect(await screen.findByText(/from the index — sampled/i)).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'Verify' }));

    // UNKNOWN is rendered as unknown. Folding it into "gone" would tell an
    // operator a message was consumed on the strength of a read that could not
    // settle the question.
    expect(await screen.findByText('unknown')).toBeInTheDocument();
    expect(screen.queryByText('gone')).not.toBeInTheDocument();
  });

  it('opens the syntax help from the keyboard and returns focus to its trigger', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    const trigger = await screen.findByRole('button', {
      name: 'Syntax and examples',
    });
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Where the query reads' })).toBeInTheDocument();
    expect(within(dialog).getByRole('heading', { name: 'What a predicate costs' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await vi.waitFor(() => expect(trigger).toHaveFocus());
  });

  it('restores the query and the tail from the URL so a console link opens as it was left', async () => {
    search.current = { q: 'SELECT * FROM "SHARED.Q" LIMIT 7', live: true };
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    const editor = await screen.findByRole('textbox', { name: 'Query' });
    await vi.waitFor(() => expect(editor.textContent).toContain('SHARED.Q'));
    expect(await screen.findByRole('switch', { name: /live tail/i })).toBeChecked();
    expect(screen.getByRole('button', { name: 'Run and tail' })).toBeInTheDocument();
    search.current = {};
  });
});

const messageRow = (messageId: number, body = `order ${messageId}`) => ({
  nodeId: 'n1',
  nodeName: 'primary',
  queueName: 'ORDER.IN',
  address: 'ORDER.IN',
  messageId,
  messageType: 3,
  durable: true,
  priority: 4,
  timestamp: 1757000000000 + messageId,
  expiration: 0,
  size: body.length,
  body,
  bodyTruncated: false,
  properties: {},
  source: 'BROKER',
  observedAt: null,
  lastSeenAt: null,
});

const doneFrame = (over: Record<string, unknown> = {}) => ({
  nodes: [answered({ matched: 1 })],
  boundsReached: [],
  notices: [],
  partial: false,
  plan: plan(),
  ...over,
});

/** What the last navigate() call writes to the address. */
function writtenSearch() {
  const call = navigate.mock.lastCall![0] as { search: () => unknown };
  return call.search();
}

describe('SqlConsoleView result outcomes', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('says no node answered rather than presenting the result as an answer', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('done', doneFrame({ nodes: [answered({ status: 'FAILED', detail: 'timed out' })] }));

    expect(await screen.findAllByText('No node answered — this result is not an answer')).toHaveLength(3);
  });

  it('counts the rows and the targets that answered, in the singular for one row', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('row', messageRow(11));
    emit('done', doneFrame());

    expect((await screen.findAllByText('1 row from 1 of 1 targets')).length).toBeGreaterThan(0);
  });

  it('shows the rows so far while the query runs, and announces that it is running', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('row', messageRow(11));
    emit('row', messageRow(12));

    expect(await screen.findByText('Running — 2 rows so far')).toBeInTheDocument();
    expect(await screen.findAllByText('Running query')).not.toHaveLength(0);
  });

  it('says nothing matched, and not that a queue is empty, when every target answered', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('done', doneFrame({ nodes: [answered({ matched: 0 })] }));

    expect(await screen.findByText('No message matched')).toBeInTheDocument();
  });

  it('says nothing has matched yet, and why, when a tail starts on an empty match', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');

    emit('done', doneFrame({ nodes: [answered({ matched: 0 })] }));

    expect(await screen.findByText('Nothing matched yet')).toBeInTheDocument();
    expect(await screen.findAllByText(/Live tail running\. 0 rows so far\./)).not.toHaveLength(0);
  });

  it('states a refusal that carries no estimate with the advice for its status', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('failed', { status: 429, title: 'Too many queries', detail: 'You have 3 queries running.' });

    expect(await screen.findByText('Too many queries')).toBeInTheDocument();
    expect(
      screen.getByText('Wait for one of your running queries to finish, then run this one again.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/against a ceiling of/)).not.toBeInTheDocument();
    expect(await screen.findAllByText('Query failed: You have 3 queries running.')).not.toHaveLength(0);
  });

  it('gives the narrowing advice for any other failure without a hint of its own', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    emit('failed', { status: 500, title: 'Query failed', detail: 'The broker reset the connection.' });

    expect(await screen.findByText(/Narrow the query — a header or property predicate/)).toBeInTheDocument();
  });
});

describe('SqlConsoleView running a query', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('writes the query into the address when it is run, and never on a keystroke', async () => {
    search.current = { q: 'SELECT * FROM "A.B"' };
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await screen.findByRole('textbox', { name: 'Query' });
    expect(navigate).not.toHaveBeenCalled();

    await run(user);

    expect(writtenSearch()).toEqual({ q: 'SELECT * FROM "A.B"', live: undefined });
    search.current = {};
  });

  it('reopens a running query in the other mode when the tail is switched, and writes it to the address', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);

    await user.click(screen.getByRole('switch', { name: /live tail/i }));

    await vi.waitFor(() => expect(EventSourceStub.instances.length).toBeGreaterThan(1));
    expect(writtenSearch()).toEqual({ q: expect.stringContaining('SELECT'), live: true });
  });

  it('drops the tail from the address when it is stopped', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', doneFrame());

    await user.click(await screen.findByRole('button', { name: /stop tailing/i }));

    expect(writtenSearch()).toEqual({ q: expect.stringContaining('SELECT') });
    expect(screen.getByRole('switch', { name: /live tail/i })).not.toBeChecked();
  });

  it('offers a run again after a dropped stream, and that opens a second stream on request only', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    act(() => EventSourceStub.instances[0].onerror?.());

    await user.click(await screen.findByRole('button', { name: /run it again/i }));

    await vi.waitFor(() => expect(EventSourceStub.instances).toHaveLength(2));
  });

  it('cannot run an empty query', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    const editor = await screen.findByRole('textbox', { name: 'Query' });
    await user.click(editor);
    await user.keyboard('{Control>}a{/Control}{Backspace}');

    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled());
  });
});

describe('SqlConsoleView permission and capability', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('keeps Run and Live tail visible but disabled, and says what is missing', async () => {
    mockCluster({ permissions: ['queue:read'] });
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Run' })).toBeDisabled());
    expect(screen.getByRole('switch', { name: /live tail/i })).toBeDisabled();
    expect(await screen.findByText('This console cannot read messages here')).toBeInTheDocument();
    expect(screen.getByText(/You do not have the "Browse messages" permission/)).toBeInTheDocument();
  });

  it('says when message access is not yet established, and offers the console anyway', async () => {
    mockCluster({ messageIo: { status: 'UNKNOWN', reason: 'not tried', brokerXmlSnippet: null } });
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByText('Not yet established for this connection')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run' })).toBeEnabled();
  });

  it('says completion is capped when the cluster has more queues than it lists', async () => {
    const queues = Array.from({ length: 500 }, (_, i) => ({ queueName: `Q.${i}` }));
    mockCluster({ queues });
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(
      await screen.findByText(/completion lists the first 500 queues on this cluster, not all of them/),
    ).toBeInTheDocument();
  });
});

describe('SqlConsoleView history', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('says it is empty at first, and that what it holds stays in this browser', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('button', { name: 'History' }));

    expect(
      await screen.findByText(/Nothing yet\. Queries you run on this browser are remembered here/),
    ).toBeInTheDocument();
  });

  it('remembers a finished run with its row count and source, loads it back without running it, and clears', async () => {
    search.current = { q: 'SELECT * FROM "REMEMBERED"' };
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    emit('row', messageRow(11));
    emit('row', messageRow(12));
    emit('done', doneFrame({ nodes: [answered({ matched: 2 })] }));
    await screen.findAllByText('2 rows from 1 of 1 targets');

    await user.click(await screen.findByRole('button', { name: 'History' }));
    // The entry, then Clear history, are the whole menu.
    const [entry] = await screen.findAllByRole('menuitem', { hidden: true });
    expect(entry).toHaveTextContent('SELECT * FROM "REMEMBERED"');
    expect(entry).toHaveTextContent('2 rows · brokers');

    search.current = {};
    await user.click(entry);
    const streams = EventSourceStub.instances.length;
    expect(screen.getByRole('textbox', { name: 'Query' }).textContent).toContain('REMEMBERED');
    expect(EventSourceStub.instances).toHaveLength(streams);

    await vi.waitFor(() => expect(screen.queryAllByRole('menuitem', { hidden: true })).toHaveLength(0));
    await user.click(screen.getByRole('button', { name: 'History' }));
    const items = await screen.findAllByRole('menuitem', { hidden: true });
    expect(items.at(-1)).toHaveTextContent('Clear history');
    await user.click(items.at(-1)!);
    await vi.waitFor(() => expect(screen.queryAllByRole('menuitem', { hidden: true })).toHaveLength(0));
    await user.click(screen.getByRole('button', { name: 'History' }));
    expect(await screen.findByText(/Nothing yet/)).toBeInTheDocument();
    expect(window.localStorage.getItem('artemis-studio.sql.history')).toBeNull();
  });

  it('names an index run as such, and a single row in the singular', async () => {
    window.localStorage.setItem(
      'artemis-studio.sql.history',
      JSON.stringify([{ sql: 'SELECT 1', at: '2026-09-01T10:00:00Z', rowCount: 1, source: 'INDEX' }]),
    );
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('button', { name: 'History' }));

    expect(await screen.findByRole('menuitem', { name: /SELECT 1/ })).toHaveTextContent('1 row · index');
  });
});

describe('SqlConsoleView result columns and export', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  async function withRows() {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    emit('row', messageRow(11, 'order, "4471"'));
    emit('done', doneFrame());
    await screen.findByRole('button', { name: 'Columns' });
    return user;
  }

  it('hides a column and shows it again in its place, remembering the choice in this browser', async () => {
    const user = await withRows();
    expect(await screen.findByRole('columnheader', { name: /Prio/ })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Columns' }));
    await user.click(await screen.findByLabelText('Prio'));

    await vi.waitFor(() => expect(screen.queryByRole('columnheader', { name: /Prio/ })).not.toBeInTheDocument());
    expect(JSON.parse(window.localStorage.getItem('artemis-studio.sql.columns')!)).not.toContain('priority');

    await user.click(screen.getByLabelText('Prio'));
    expect(await screen.findByRole('columnheader', { name: /Prio/ })).toBeInTheDocument();
    const stored: string[] = JSON.parse(window.localStorage.getItem('artemis-studio.sql.columns')!);
    expect(stored.indexOf('priority')).toBe(stored.indexOf('timestamp') + 1);
  });

  it('moves a column earlier and later, and leaves the first and last where they are', async () => {
    const user = await withRows();
    await user.click(screen.getByRole('button', { name: 'Columns' }));

    await user.click(await screen.findByLabelText('Move Node earlier'));
    let stored: string[] = JSON.parse(window.localStorage.getItem('artemis-studio.sql.columns')!);
    expect(stored.slice(0, 2)).toEqual(['node', 'source']);

    await user.click(screen.getByLabelText('Move Node earlier'));
    stored = JSON.parse(window.localStorage.getItem('artemis-studio.sql.columns')!);
    expect(stored.slice(0, 2)).toEqual(['node', 'source']);

    await user.click(screen.getByLabelText('Move Node later'));
    stored = JSON.parse(window.localStorage.getItem('artemis-studio.sql.columns')!);
    expect(stored.slice(0, 2)).toEqual(['source', 'node']);
  });

  it('starts from the columns this browser remembered, ignoring any it does not know', async () => {
    window.localStorage.setItem('artemis-studio.sql.columns', JSON.stringify(['body', 'nonsense', 'node']));
    const user = await withRows();
    await user.click(screen.getByRole('button', { name: 'Columns' }));

    expect(await screen.findByLabelText('Body')).toBeChecked();
    expect(screen.getByLabelText('Node')).toBeChecked();
    expect(screen.getByLabelText('Prio')).not.toBeChecked();
  });

  it.each([
    ['not a list', JSON.stringify({ a: 1 })],
    ['nothing known', JSON.stringify(['nonsense'])],
    ['not JSON', '{'],
  ])('falls back to every column when the remembered ones are %s', async (_name, stored) => {
    window.localStorage.setItem('artemis-studio.sql.columns', stored);
    const user = await withRows();
    await user.click(screen.getByRole('button', { name: 'Columns' }));

    expect(await screen.findByLabelText('Prio')).toBeChecked();
  });

  it('exports exactly the rows in view as CSV and JSON, saying they may be a prefix', async () => {
    const files: { name: string; blob: Blob }[] = [];
    let last: Blob | null = null;
    URL.createObjectURL = vi.fn((blob: Blob | MediaSource) => {
      last = blob as Blob;
      return 'blob:sql';
    });
    URL.revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      files.push({ name: this.download, blob: last! });
    });
    const user = await withRows();

    expect(
      screen.getByText(/Export writes the 1 row in view, which may be a prefix of the answer/),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'CSV' }));
    await user.click(screen.getByRole('button', { name: 'JSON' }));

    expect(files.map((f) => f.name)).toEqual(['sql-console.csv', 'sql-console.json']);
    const csv = await files[0].blob.text();
    expect(csv.split('\r\n')[0]).toContain('queue,address,messageId');
    expect(csv).toContain('"order, ""4471"""');
    const json = JSON.parse(await files[1].blob.text());
    expect(json).toHaveLength(1);
    expect(json[0]).toMatchObject({ queue: 'ORDER.IN', messageId: 11, body: 'order, "4471"' });
    vi.restoreAllMocks();
  });
});

describe('SqlConsoleView editor height', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  const handle = () => screen.getByRole('slider', { name: 'Editor height' });

  it('starts at the height this browser remembered, held inside the limits', async () => {
    window.localStorage.setItem('artemis-studio.sql.editorFraction', '0.9');
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByRole('slider', { name: 'Editor height' })).toHaveAttribute('aria-valuenow', '70');
  });

  it('falls back to the default height when the remembered one is not a number', async () => {
    window.localStorage.setItem('artemis-studio.sql.editorFraction', 'tall');
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);

    expect(await screen.findByRole('slider', { name: 'Editor height' })).toHaveAttribute('aria-valuenow', '32');
  });

  it('resizes from the keyboard, says the value, stops at the limits and remembers it', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await screen.findByRole('slider', { name: 'Editor height' });

    handle().focus();
    await user.keyboard('{ArrowDown}');
    expect(handle()).toHaveAttribute('aria-valuenow', '35');
    expect(handle()).toHaveAttribute('aria-valuetext', 'Editor takes 35 percent of the console');
    expect(Number(window.localStorage.getItem('artemis-studio.sql.editorFraction'))).toBeCloseTo(0.35);

    await user.keyboard('{ArrowUp}{ArrowUp}');
    expect(handle()).toHaveAttribute('aria-valuenow', '29');

    for (let i = 0; i < 20; i++) await user.keyboard('{ArrowUp}');
    expect(handle()).toHaveAttribute('aria-valuenow', '15');

    await user.keyboard('a');
    expect(handle()).toHaveAttribute('aria-valuenow', '15');
  });

  it('resizes by dragging the handle, and remembers where it was let go', async () => {
    mockCluster();
    mockPlan();
    renderWithProviders(<SqlConsoleView />);
    await screen.findByRole('slider', { name: 'Editor height' });

    // The jsdom box is 800 high, so 80 pixels down is a tenth of the console.
    fireEvent.pointerDown(handle(), { clientY: 100 });
    act(() => {
      window.dispatchEvent(new MouseEvent('pointermove', { clientY: 180 }));
    });
    expect(handle()).toHaveAttribute('aria-valuenow', '42');
    act(() => {
      window.dispatchEvent(new MouseEvent('pointerup'));
    });

    expect(Number(window.localStorage.getItem('artemis-studio.sql.editorFraction'))).toBeCloseTo(0.42);
  });
});

describe('SqlConsoleView other panels', () => {
  beforeEach(() => {
    window.localStorage.clear();
    navigate.mockReset();
  });

  it('loads an example from the syntax help into the editor, and closes the help', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('button', { name: 'Syntax and examples' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getAllByRole('button', { name: 'Load into editor' })[0]);

    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('textbox', { name: 'Query' }).textContent).not.toContain(
      'ORDER BY timestamp DESC\nLIMIT 100',
    );
  });

  it('opens a result row in the message detail, and closes it again', async () => {
    mockCluster();
    mockPlan();
    server.use(
      http.get('*/api/v1/clusters/c1/queues/ORDER.IN/messages/11', () =>
        HttpResponse.json({ title: 'Message gone', detail: 'It was consumed.' }, { status: 404 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);
    await run(user);
    emit('row', messageRow(11, 'order 4471'));
    emit('done', doneFrame());

    await user.click(await screen.findByText('order 4471'));

    const dialog = await screen.findByRole('dialog');
    await user.keyboard('{Escape}');
    await vi.waitFor(() => expect(dialog).not.toBeInTheDocument());
  });
});
