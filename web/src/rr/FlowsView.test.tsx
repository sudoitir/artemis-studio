import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => ({}),
  useNavigate: () => () => {},
}));

const { FlowsView } = await import('./FlowsView.tsx');

function cluster(notifStatus: string) {
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
      },
    },
    health: {
      clusterId: 'c1',
      level: 'OK',
      liveEndpointNames: [],
      splitBrain: 'NONE',
      replicationBehind: false,
      notes: [],
    },
  };
}

function flow(over: Record<string, unknown> = {}) {
  return {
    id: 'f1',
    clusterId: 'c1',
    nodeId: null,
    requestAddress: 'orders.request',
    replyDestination: null,
    replyKind: 'SHARED_QUEUE',
    state: 'AWAITING_REPLY',
    correlationId: 'corr-1',
    requestedAt: '2026-09-04T10:00:00.000Z',
    deadlineAt: '2026-09-04T10:00:30.000Z',
    repliedAt: null,
    latencyMs: null,
    ...over,
  };
}

describe('FlowsView', () => {
  it('shows the reason and broker.xml snippet when tracing is not available', async () => {
    server.use(http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('UNAVAILABLE'))));
    renderWithProviders(<FlowsView />);

    expect(await screen.findByText(/refused the subscription/)).toBeInTheDocument();
    expect(screen.getByText(/activemq\.notifications/)).toBeInTheDocument();
  });

  it('lists flows and opens the detail drawer on selection', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () =>
        HttpResponse.json({ data: [flow()], count: 1, page: 1, pageSize: 100 }),
      ),
      http.get('*/api/v1/clusters/c1/rr/flows/f1', () =>
        HttpResponse.json({ ...flow(), events: [{ seq: 1, ts: '2026-09-04T10:00:00.000Z', kind: 'REQUEST_SEEN', nodeId: null, detail: null }] }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<FlowsView />);

    expect(await screen.findByText('corr-1')).toBeInTheDocument();
    await user.click(screen.getByText('corr-1'));
    expect(await screen.findByText('REQUEST_SEEN')).toBeInTheDocument();
  });

  it('shows an empty state with no flows', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json(cluster('AVAILABLE'))),
      http.get('*/api/v1/clusters/c1/rr/flows', () =>
        HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 100 }),
      ),
    );
    renderWithProviders(<FlowsView />);

    expect(await screen.findByText(/No request-reply flows observed yet/)).toBeInTheDocument();
  });
});
