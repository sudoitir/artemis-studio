import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const navigateSpy = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
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
      slowConsumerDetection: { status: 'UNKNOWN', reason: 'not exposed', brokerXmlSnippet: '<x/>' },
      versionGates: [],
    },
    reachableSeeds: 1,
    discoveredNodes: 2,
    contributions: {
      brokerconfig: {
        seededFrom: 'broker-1',
        recommendations: [
          {
            capability: 'slowConsumerDetection',
            title: 'Let the broker detect slow consumers',
            rationale: 'The broker sees each consumer own delivery rate.',
            appliable: true,
            section: 'ADDRESS_SETTING',
            match: '#',
            values: { maxDeliveryAttempts: 7, slowConsumerThreshold: 1 },
            roles: {},
            keys: ['slowConsumerThreshold'],
            manualSnippet: null,
          },
        ],
      },
    },
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
              versionSupport: 'SUPPORTED',
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

afterEach(() => act(() => notifications.clean()));

describe('RegisterClusterForm', () => {
  it('swaps the preview placeholder for the real discovered topology after a successful check', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    expect(screen.getByText('Topology preview')).toBeInTheDocument();

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Discovered topology')).toBeInTheDocument();
    expect(screen.queryByText('Topology preview')).not.toBeInTheDocument();
  });

  it('tells the operator which URLs to enter for the setup they choose', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);
    const urls = screen.getByLabelText(/Broker management URLs/);
    expect(urls).toHaveAccessibleDescription(/^One per line\./);

    await user.click(screen.getByRole('radio', { name: /Primary and backup/ }));
    expect(screen.getByRole('radio', { name: /Primary and backup/ })).toBeChecked();
    expect(urls).toHaveAccessibleDescription(/Enter the primary's management URL\. Studio finds its backup/);

    await user.click(screen.getByRole('radio', { name: /Cluster/ }));
    expect(urls).toHaveAccessibleDescription(/one management URL per broker Studio can reach/);
  });

  it('marks the preview stale once the seeds are edited after a check', async () => {
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(preview())));
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText('Discovered topology');

    await user.type(screen.getByLabelText(/Broker management URLs/), '\nbroker-2');
    expect(await screen.findByText('Changed since you checked')).toBeInTheDocument();
  });

  it('warns before registering a broker newer than Studio has tested, and still lets it register', async () => {
    const untested = preview();
    untested.topology.nodes[0].endpoints[0].version = '2.60.0';
    untested.topology.nodes[0].endpoints[0].versionSupport = 'NEWER_THAN_TESTED';
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(untested)));
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Newer Artemis than Studio has tested')).toBeInTheDocument();
    expect(screen.getByText(/broker-1 runs Artemis 2\.60\.0/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeEnabled();
  });

  it('states why a broker older than the supported minimum is refused', async () => {
    server.use(
      http.post('*/api/v1/clusters', () =>
        HttpResponse.json(
          {
            type: 'https://artemis-studio.dev/problems/broker-unsupported-version',
            title: 'Artemis version not supported',
            status: 422,
            detail:
              'The broker at http://broker-1:8161/console/jolokia runs Artemis 2.31.2. Studio supports Artemis 2.33.0 and later; upgrade the broker to register it.',
            brokerErrorKind: 'UNSUPPORTED_VERSION',
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText(/Studio supports Artemis 2\.33\.0 and later/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register cluster' })).toBeDisabled();
    // Nothing was edited, so the hint must not claim the details changed.
    expect(screen.getByText('The check failed. Fix what it reports above, then check again.')).toBeInTheDocument();
  });

  it('lists an operation the brokers are too old for with the release it needs', async () => {
    const gated = preview();
    (gated.capabilities.versionGates as unknown[]).push({
      feature: 'X',
      label: 'Doing X',
      requiredVersion: '2.60.0',
      status: 'UNAVAILABLE',
      reason: 'Doing X needs Artemis 2.60.0 or later. This cluster runs broker-1 (2.40.0).',
      brokerXmlSnippet: null,
      nodes: [{ nodeId: 'e1', nodeName: 'broker-1', version: '2.40.0', supported: false }],
    });
    server.use(http.post('*/api/v1/clusters', () => HttpResponse.json(gated)));
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByRole('button', { name: /Doing X\s*Needs Artemis 2\.60\.0/ })).toBeInTheDocument();
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

  it('answers an empty press with the message beside the field, focuses it and sends nothing', async () => {
    let sent = 0;
    server.use(
      http.post('*/api/v1/clusters', () => {
        sent += 1;
        return HttpResponse.json(preview());
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    const check = screen.getByRole('button', { name: 'Check connection' });
    expect(check).toBeEnabled();
    expect(screen.getByText('Fill in the fields marked above, then check the connection.')).toBeInTheDocument();
    await user.click(check);

    expect(await screen.findByText('Add at least one management URL.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Broker management URLs/)).toHaveFocus();
    expect(sent).toBe(0);
  });

  it('focuses the first invalid field of several, in page order', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterForm />);

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.type(screen.getByLabelText('Username'), 'artemis');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));

    expect(await screen.findByText('Provide both a username and a password, or neither.')).toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toHaveFocus();
  });

  it('registers a checked cluster, announces it by name and opens its topology', async () => {
    server.use(
      http.post('*/api/v1/clusters', ({ request }) =>
        new URL(request.url).searchParams.get('dryRun') === 'true'
          ? HttpResponse.json(preview())
          : HttpResponse.json({ id: 'c1', name: 'prod-eu' }, { status: 201 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <Notifications />
        <RegisterClusterForm />
      </>,
    );

    await user.type(screen.getByLabelText(/Broker management URLs/), 'broker-1');
    await user.click(screen.getByRole('button', { name: 'Check connection' }));
    await screen.findByText('Discovered topology');
    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    expect(await screen.findByText('Registered cluster prod-eu')).toBeInTheDocument();
    expect(navigateSpy).toHaveBeenCalledWith({ to: '/clusters/c1/topology' });
  });
});
