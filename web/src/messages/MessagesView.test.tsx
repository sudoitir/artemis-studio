import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

vi.mock('@tanstack/react-router', () => ({
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
});
