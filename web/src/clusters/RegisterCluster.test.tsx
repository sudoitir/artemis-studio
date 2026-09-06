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

const { RegisterClusterForm, RegisterClusterButton } = await import('./RegisterCluster.tsx');

function preview() {
  return {
    capabilities: {
      managementRead: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      managementWrite: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      notifications: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      messageIo: { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null },
      slowConsumerDetection: { status: 'UNKNOWN', reason: 'not exposed', brokerXmlSnippet: '<x/>' },
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

  it('will not register until the connection has been checked, and says so', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');

    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    // Disabled without a reason is the thing the form must never do.
    expect(screen.getByText('Check the connection first.')).toBeInTheDocument();
  });

  it('enables registration once the check passes', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText('Discovered topology');

    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeEnabled();
  });

  it('re-blocks registration when a credential changes after a passing check', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText('Discovered topology');

    // A check that survived a password edit would vouch for credentials it never
    // saw — which is exactly how a wrong Core account reached a registered cluster.
    await user.type(screen.getByLabelText('Username'), 'artemis');
    await user.type(screen.getByLabelText('Password'), 'secret');

    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    expect(
      await screen.findByText('Check the connection again — the details changed since the last check.'),
    ).toBeInTheDocument();
  });
});

describe('RegisterClusterButton', () => {
  it('tells the operator this is an add when a cluster already exists', async () => {
    server.use(
      http.get('*/api/v1/clusters', () =>
        HttpResponse.json([
          { id: 'c1', name: 'prod-emea', health: 'OK', nodeCount: 2, updatedAt: '2026-01-01T00:00:00Z' },
        ]),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterButton />);

    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    expect(
      await screen.findByText(/one cluster is already registered\. this adds another\./i),
    ).toBeInTheDocument();
  });

  it('renders an icon-only trigger with an accessible name when the rail is collapsed', () => {
    server.use(http.get('*/api/v1/clusters', () => HttpResponse.json([])));
    renderWithProviders(<RegisterClusterButton collapsed />);

    const trigger = screen.getByRole('button', { name: 'Register cluster' });
    // The collapsed trigger is an ActionIcon: its accessible name comes from
    // aria-label, not visible text.
    expect(trigger).toHaveAttribute('aria-label', 'Register cluster');
    expect(trigger).not.toHaveTextContent('Register cluster');
  });
});
