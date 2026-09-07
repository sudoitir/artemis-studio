import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { EventSourceStub, server } from '../test/setup.ts';

const search = { current: {} as { q?: string; live?: boolean } };

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search.current,
  useNavigate: () => () => {},
}));

const { SqlConsoleView } = await import('./SqlConsoleView.tsx');

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function target(nodeId: string, nodeName: string) {
  return { nodeId, nodeName, queueName: 'ORDER.IN', address: 'ORDER.IN', messageCount: 1200 };
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

function mockCluster() {
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
          notifications: { status: 'UNKNOWN', reason: 'n/a', brokerXmlSnippet: null },
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
    http.get('*/api/v1/clusters/c1/queues', () =>
      HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 500 }),
    ),
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        username: 'op',
        displayName: 'Op',
        provider: 'LOCAL',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, roleName: 'admin', permissions: ['*'] }],
      }),
    ),
  );
}

function mockPlan(body: Record<string, unknown> = plan()) {
  server.use(http.post('*/api/v1/clusters/c1/sql/plan', () => HttpResponse.json(body)));
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

    // Twice on purpose: once in the aria-live region and once as the headline.
    expect(await screen.findAllByText(/incomplete, this is a prefix of the answer/i)).toHaveLength(
      2,
    );
    expect(screen.getByText('connection refused')).toBeInTheDocument();
    expect(screen.getByText(/the scan cap/i)).toBeInTheDocument();
    // An unanswered node is not an empty result, and must not be presented as one.
    expect(screen.getByText(/not because there was nothing/i)).toBeInTheDocument();
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

    expect(
      await screen.findByText(/900,000 messages, against a ceiling of 250,000/i),
    ).toBeInTheDocument();
    expect(screen.getByText(/add a header predicate/i)).toBeInTheDocument();
  });

  it('states the sampled-tail limitation while tailing, with no way to dismiss it', async () => {
    mockCluster();
    mockPlan();
    const user = userEvent.setup();
    renderWithProviders(<SqlConsoleView />);

    await user.click(await screen.findByRole('switch', { name: /live tail/i }));
    await run(user, 'Run and tail');
    emit('done', { nodes: [answered()], boundsReached: [], notices: [], partial: false, plan: plan() });
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
    emit('done', { nodes: [answered()], boundsReached: [], notices: [], partial: false, plan: plan() });

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

    // The result says where it came from once, not only in a badge column.
    expect(await screen.findByText(/Answered from the index/i)).toBeInTheDocument();

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

    const trigger = await screen.findByRole('button', { name: 'Syntax and examples' });
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog');
    expect(
      within(dialog).getByRole('heading', { name: 'Where the query reads' }),
    ).toBeInTheDocument();
    expect(
      within(dialog).getByRole('heading', { name: 'What a predicate costs' }),
    ).toBeInTheDocument();

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
