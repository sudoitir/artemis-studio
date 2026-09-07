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
});
