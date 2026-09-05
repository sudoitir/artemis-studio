import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

const navigateSpy = vi.fn();
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigateSpy,
}));

const { RegisterClusterForm } = await import('./RegisterCluster.tsx');

function preview() {
  return {
    capabilities: {
      managementRead: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      managementWrite: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      notifications: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      messageIo: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
    },
    reachableSeeds: 1,
    discoveredNodes: 2,
    topology: {
      clusterId: 'preview',
      nodes: [
        {
          artemisNodeId: 'node-a',
          splitBrain: 'NONE',
          replicationBehind: false,
          endpoints: [
            {
              id: 'e1',
              name: 'broker-1',
              artemisNodeId: 'node-a',
              jolokiaUrl: 'http://broker-1:8161/console/jolokia',
              coreUrl: null,
              haRole: 'PRIMARY',
              state: 'STARTED',
              active: true,
              replicaSync: null,
              version: '2.40.0',
              lastError: null,
              lastSeenAt: null,
              discovered: false,
              manualOverride: false,
              manageable: true,
            },
          ],
        },
      ],
    },
  };
}

describe('RegisterClusterForm', () => {
  it('swaps the canvas from examples to the real discovered topology after a successful check', async () => {
    server.use(
      http.post('*/api/v1/clusters', () => HttpResponse.json(preview())),
    );
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    expect(screen.getByText('Single broker')).toBeInTheDocument();

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Discovered topology')).toBeInTheDocument();
    expect(screen.queryByText('Single broker')).not.toBeInTheDocument();
  });

  it('marks the preview stale once the seeds are edited after a check', async () => {
    server.use(
      http.post('*/api/v1/clusters', () => HttpResponse.json(preview())),
    );
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText('Discovered topology');

    await user.type(screen.getByLabelText(/Broker management URLs/), '\nbroker-2');
    expect(await screen.findByText('Changed since you checked')).toBeInTheDocument();
  });
});
