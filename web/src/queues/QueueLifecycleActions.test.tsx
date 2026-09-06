import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import type { CapabilityView, QueueView } from '../api/client.ts';
import { QueueLifecycleActions } from './QueueLifecycleActions.tsx';

const AVAILABLE: CapabilityView = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function capabilities(managementWrite: CapabilityView) {
  return {
    managementRead: AVAILABLE,
    managementWrite,
    notifications: AVAILABLE,
    messageIo: AVAILABLE,
    slowConsumerDetection: AVAILABLE,
  };
}

/** The cluster detail carries the capability ledger the controls gate on. */
function clusterHandler(managementWrite: CapabilityView) {
  return http.get('*/api/v1/clusters/c1', () =>
    HttpResponse.json({
      id: 'c1',
      name: 'c1',
      description: null,
      topology: { nodes: [] },
      capabilities: capabilities(managementWrite),
      health: { level: 'OK', reasons: [] },
      environmentId: null,
    }),
  );
}

/** An admin with every grant, so the permission gate never masks the capability gate. */
function meHandler(permissions: string[] = ['*']) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'admin',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

function queue(over: Partial<QueueView> = {}): QueueView {
  return {
    address: 'orders.addr',
    queueName: 'orders',
    routingType: 'ANYCAST',
    durable: true,
    totalMessageCount: 12,
    totalConsumerCount: 0,
    totalDeliveringCount: 0,
    totalScheduledCount: 0,
    nodesPresent: 1,
    nodesTotal: 1,
    paused: false,
    perNode: [
      {
        nodeId: 'n1',
        nodeName: 'node-a',
        stale: false,
        lastSeenAt: null,
        messageCount: 12,
        consumerCount: 0,
        deliveringCount: 0,
        scheduledCount: 0,
        paused: false,
      },
    ],
    ...over,
  } as QueueView;
}

function Harness() {
  return <QueueLifecycleActions clusterId="c1" queue={queue()} onClose={() => {}} />;
}

describe('QueueLifecycleActions capability gating', () => {
  it('offers the actions and states the uncertainty when the capability is unknown', async () => {
    server.use(
      meHandler(),
      clusterHandler({
        status: 'UNKNOWN',
        reason: 'No management write has been attempted on this connection yet.',
        brokerXmlSnippet: null,
      }),
    );
    renderWithProviders(<Harness />);

    // The uncertainty is stated once the capability ledger has loaded...
    expect(
      await screen.findByText(/has not yet seen a management write on this connection/i),
    ).toBeInTheDocument();
    // ...and absence of evidence still does not block the operator.
    expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Pause' })).toBeEnabled();
  });

  it('keeps the action visible but disabled, with the reason, when the capability is refused', async () => {
    server.use(
      meHandler(),
      clusterHandler({
        status: 'UNAVAILABLE',
        reason: 'The broker refused a management write for these credentials.',
        brokerXmlSnippet: '<security-setting match="activemq.management.#"/>',
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    // Visible and disabled — never silently absent (non-negotiable #5).
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Delete queue' })).toBeDisabled(),
    );

    // And the reason is reachable without a pointer hover: the wrapper is a real
    // focusable control carrying the explanation.
    const why = screen.getAllByRole('button', { name: 'Why this is unavailable' })[0];
    await user.click(why);

    expect(
      await screen.findByText('The broker refused a management write for these credentials.'),
    ).toBeInTheDocument();
    expect(screen.getByText(/security-setting match/)).toBeInTheDocument();
  });

  it('disables an action the caller has no permission for, and says which permission', async () => {
    // Every lifecycle permission except delete.
    server.use(
      meHandler(['queue:create', 'queue:update', 'queue:pause', 'cluster:read']),
      clusterHandler(AVAILABLE),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Delete queue' })).toBeDisabled(),
    );
    expect(screen.getByRole('button', { name: 'Pause' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Why this is unavailable' }));
    expect(await screen.findByText(/Destroy queues and addresses/)).toBeInTheDocument();
  });
});

describe('the destructive flow is keyboard-complete', () => {
  it('opens on a preview, arms only on the typed name, and closes on escape', async () => {
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/queues/orders', () =>
        HttpResponse.json({
          dryRun: true,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: 12,
          nodes: [
            { nodeId: 'n1', nodeName: 'node-a', status: 'WOULD_APPLY', affected: 12, error: null },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    // Wait for the gate to settle so the control is the real, ungated button.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled(),
    );
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));

    const dialog = await screen.findByRole('dialog');
    // The blast radius is named before the confirmation can arm.
    expect(await within(dialog).findByText('would destroy 12 messages')).toBeInTheDocument();
    expect(within(dialog).getByText('node-a')).toBeInTheDocument();

    const confirm = within(dialog).getByRole('button', { name: 'Delete this queue' });
    expect(confirm).toBeDisabled();

    // A near-miss must not arm it.
    const field = within(dialog).getByRole('textbox');
    await user.type(field, 'order');
    expect(within(dialog).getByRole('button', { name: 'Delete this queue' })).toBeDisabled();

    await user.type(field, 's');
    await waitFor(() =>
      expect(within(dialog).getByRole('button', { name: 'Delete this queue' })).toBeEnabled(),
    );

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });
});
