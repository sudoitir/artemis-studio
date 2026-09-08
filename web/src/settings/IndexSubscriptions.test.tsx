import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
}));

const { IndexSubscriptions } = await import('./IndexSubscriptions.tsx');

function subscription(over: Record<string, unknown> = {}) {
  return {
    id: 's1',
    queuePattern: 'ORDER.IN',
    retentionDays: 7,
    intervalMs: 5000,
    captureFrom: '2026-09-01T09:00:00Z',
    createdAt: '2026-09-01T09:00:00Z',
    createdBy: 'op',
    enabled: true,
    messagesHeld: 1284,
    bytesHeld: 2_600_000,
    oldestObservedAt: '2026-09-01T09:00:03Z',
    ...over,
  };
}

function mockMe() {
  server.use(
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

describe('IndexSubscriptions', () => {
  it('states that message bodies will be stored before the subscription is created', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json([])));
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Queue or pattern'), 'ORDER.IN');

    // The disclosure is on the form, not behind a link: this is the last screen
    // before Studio starts keeping a copy of application payload.
    expect(screen.getByText(/This stores message bodies/i)).toBeInTheDocument();
    expect(screen.getByText(/headers, application properties and the body/i)).toBeInTheDocument();
    expect(screen.getByText(/for 7 days/i)).toBeInTheDocument();
  });

  it('teaches what an index is when none exists, rather than showing an empty table', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json([])));
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText(/Nothing is being indexed/i)).toBeInTheDocument();
    expect(screen.getByText(/cannot find a message that has already been consumed/i)).toBeInTheDocument();
  });

  it('shows what a subscription is holding', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json([subscription()])),
    );
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText('ORDER.IN')).toBeInTheDocument();
    expect(screen.getByText(/1,284 messages/)).toBeInTheDocument();
    expect(screen.getByText(/2\.5 MB of payload/)).toBeInTheDocument();
  });

  it('states the blast radius and needs the pattern typed before it will delete', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json([subscription()])),
      http.delete('*/api/v1/clusters/c1/sql/index/s1', () =>
        HttpResponse.json({ messagesDestroyed: 1284 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.click(await screen.findByRole('button', { name: 'Delete' }));

    expect(screen.getByText(/1,284 captured messages/)).toBeInTheDocument();
    const confirm = screen.getByRole('button', { name: /Delete and destroy captured messages/i });
    // Not armed by a click, and not by a checkbox: the resource's own name.
    expect(confirm).toBeDisabled();

    await user.type(screen.getByLabelText('Type "ORDER.IN" to confirm'), 'ORDER.IN');
    expect(confirm).toBeEnabled();
  });

  it('states the full blast radius before capture can be started', async () => {
    mockMe();
    server.use(http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json([])));
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    await user.type(await screen.findByLabelText('Queue or pattern'), 'ORDER.IN');
    await user.click(screen.getByRole('radio', { name: /capture everything/i }));

    // Every object Studio will create on the broker, named, before the button that
    // creates them can be pressed.
    expect(screen.getByText(/changes routing on every live node/i)).toBeInTheDocument();
    expect(screen.getByText(/non-exclusive divert/i)).toBeInTheDocument();
    expect(screen.getByText(/ring-bounded, non-durable queue/i)).toBeInTheDocument();
    expect(screen.getByText(/security setting/i)).toBeInTheDocument();
    // ADR-0065: nothing here is temporary, and the screen must not imply otherwise.
    expect(
      screen.getByText(/None of those objects disappears when the broker restarts/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/lost when the broker restarts/i)).not.toBeInTheDocument();
    // The configuration equivalent, for an estate that deploys from broker.xml.
    expect(screen.getByText('artemis-studio.capture.#')).toBeInTheDocument();
  });

  it('reports capture state per node, never as one rolled-up answer', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json([
          subscription({
            mode: 'CAPTURE',
            maxBytes: 5_368_709_120,
            nodes: [
              {
                nodeId: 'n1',
                nodeName: 'primary',
                state: 'ACTIVE',
                detail: null,
                capturedFrom: '2026-09-07T09:00:00Z',
                droppedEstimate: 0,
              },
              {
                nodeId: 'n2',
                nodeName: 'backup',
                state: 'FAILED',
                detail: 'an exclusive divert on ORDER.IN would shadow the capture divert',
                capturedFrom: null,
                droppedEstimate: 0,
              },
            ],
          }),
        ]),
      ),
    );
    renderWithProviders(<IndexSubscriptions />);

    expect(await screen.findByText(/primary: capturing since/i)).toBeInTheDocument();
    // The node that is not capturing says so, and says why. A single "capturing:
    // yes" would erase exactly the gap the operator needs.
    expect(screen.getByText(/backup: not capturing/i)).toBeInTheDocument();
    expect(screen.getByText(/exclusive divert/i)).toBeInTheDocument();
  });

  it('can be driven to capture-delete on the keyboard alone, and returns focus', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json([subscription({ mode: 'CAPTURE', nodes: [] })]),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    const trigger = await screen.findByRole('button', { name: 'Delete' });
    trigger.focus();
    expect(trigger).toHaveFocus();

    await user.keyboard('{Enter}');

    // The confirmation is reachable and armable without a pointer.
    const field = await screen.findByLabelText('Type "ORDER.IN" to confirm');
    const confirm = screen.getByRole('button', {
      name: /Delete and destroy captured messages/i,
    });
    expect(confirm).toBeDisabled();
    field.focus();
    await user.keyboard('ORDER.IN');
    expect(confirm).toBeEnabled();

    // Cancelling returns focus to the control that opened it, rather than dropping
    // the operator back at the top of the document.
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    cancel.focus();
    await user.keyboard('{Enter}');
    const again = await screen.findByRole('button', { name: 'Delete' });
    again.focus();
    expect(again).toHaveFocus();
  });

  it('can be driven to start capture on the keyboard alone', async () => {
    mockMe();
    server.use(
      http.get('*/api/v1/clusters/c1/sql/index', () => HttpResponse.json([])),
      http.post('*/api/v1/clusters/c1/sql/index', () =>
        HttpResponse.json(subscription({ mode: 'CAPTURE', nodes: [] })),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<IndexSubscriptions />);

    const pattern = await screen.findByLabelText('Queue or pattern');
    pattern.focus();
    await user.keyboard('ORDER.IN');

    // The mode is a radio group, so it is reachable and switchable with the arrow
    // keys — no pointer, and no control that only a mouse can reach.
    const sample = screen.getByRole('radio', { name: /^sample$/i });
    sample.focus();
    await user.keyboard('{ArrowRight}');
    expect(screen.getByText(/changes routing on every live node/i)).toBeInTheDocument();

    const start = screen.getByRole('button', { name: /Start capturing/i });
    expect(start).toBeEnabled();
    start.focus();
    expect(start).toHaveFocus();
  });
});
