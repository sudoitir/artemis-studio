import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server, EventSourceStub } from '../test/setup.ts';
import { waitFor } from '@testing-library/react';

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => ({}),
  useNavigate: () => () => {},
}));

const { EventsView } = await import('./EventsView.tsx');

function cluster(notifStatus: string, extra: Record<string, unknown> = {}) {
  return {
    id: 'c1',
    name: 'prod',
    description: null,
    topology: { clusterId: 'c1', nodes: [], unmanaged: [] },
    capabilities: {
      managementRead: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      managementWrite: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      messageIo: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      notifications: {
        status: notifStatus,
        reason: 'the broker refused the subscription',
        brokerXmlSnippet: '<security-setting match="activemq.notifications"/>',
        ...extra,
      },
    },
    health: { clusterId: 'c1', level: 'OK', liveEndpointNames: [], splitBrain: 'NONE', replicationBehind: false, notes: [] },
  };
}

function event(over: Record<string, unknown> = {}) {
  return {
    seq: 1,
    occurredAt: '2026-09-04T10:00:00.000Z',
    receivedAt: '2026-09-04T10:00:00.000Z',
    type: 'CONSUMER_CREATED',
    address: 'orders',
    routingName: 'orders',
    consumerName: 'c-42',
    sessionName: 's-1',
    connectionName: 'conn-1',
    remoteAddress: '10.0.0.9:5445',
    username: 'alice',
    nodeId: null,
    props: { _AMQ_NotifType: 'CONSUMER_CREATED', _AMQ_Address: 'orders' },
    ...over,
  };
}

describe('EventsView', () => {
  it('lists events and expands a row to show the raw props', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () =>
        HttpResponse.json({
          data: [event()],
          count: 1,
          page: 1,
          pageSize: 100,
          dropped: 0,
          oldestRetained: '2026-09-04T09:00:00.000Z',
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<EventsView />);

    expect(await screen.findByText('CONSUMER_CREATED')).toBeInTheDocument();
    await user.click(screen.getByText('CONSUMER_CREATED'));
    expect(await screen.findByText(/_AMQ_Address/)).toBeInTheDocument();
  });

  it('shows the reason and broker.xml snippet when notifications are unavailable', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('UNAVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100, dropped: 0, oldestRetained: null }),
      ),
    );
    renderWithProviders(<EventsView />);

    expect(await screen.findByText(/refused the subscription/)).toBeInTheDocument();
    expect(screen.getByText(/activemq\.notifications/)).toBeInTheDocument();
  });

  it('appends a live event pushed over the stream and does not duplicate on a repeat seq', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100, dropped: 0, oldestRetained: null }),
      ),
    );
    renderWithProviders(<EventsView />);

    await screen.findByText(/No broker events recorded yet/);
    await waitFor(() => expect(EventSourceStub.instances.length).toBeGreaterThan(0));

    EventSourceStub.emit('events', event({ seq: 99, type: 'SESSION_CREATED', remoteAddress: '10.9.9.9:1' }));
    expect(await screen.findByText('10.9.9.9:1')).toBeInTheDocument();

    // Re-delivering the same seq must not add a second row (remoteAddress is row-only).
    EventSourceStub.emit('events', event({ seq: 99, type: 'SESSION_CREATED', remoteAddress: '10.9.9.9:1' }));
    await waitFor(() => expect(screen.getAllByText('10.9.9.9:1')).toHaveLength(1));
  });

  it('warns when events have been dropped', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/events', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100, dropped: 7, oldestRetained: null }),
      ),
    );
    renderWithProviders(<EventsView />);

    expect(await screen.findByText(/were dropped/i)).toBeInTheDocument();
  });
});
