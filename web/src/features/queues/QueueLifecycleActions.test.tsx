import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { CapabilityView, QueueView } from './api.ts';
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

  it('names the consumers and dependent diverts, and disconnects only when asked, by keyboard alone', async () => {
    const urls: string[] = [];
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/queues/orders', ({ request }) => {
        const url = new URL(request.url);
        urls.push(url.search);
        const dryRun = url.searchParams.get('dryRun') === 'true';
        const disconnect = url.searchParams.get('disconnectConsumers') === 'true';
        const note =
          "2 consumers will be disconnected. Removed with the queue, because they forward into 'orders.addr'" +
          " and this is its last queue: 'feed' (incoming → orders.addr, routing name feed, routing type STRIP).";
        const node = !disconnect
          ? {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: 'FAILED',
              affected: null,
              error: "Queue 'orders' has 2 consumers attached on this node. Delete with disconnectConsumers.",
            }
          : {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: dryRun ? 'WOULD_APPLY' : 'APPLIED',
              affected: 12,
              error: note,
            };
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: node.affected ?? 0,
          nodes: [node],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <QueueLifecycleActions
        clusterId="c1"
        queue={queue({ totalConsumerCount: 2 })}
        onClose={() => {}}
      />,
    );

    const trigger = screen.getByRole('button', { name: 'Delete queue' });
    await waitFor(() => expect(trigger).toBeEnabled());
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement));
    // Without the flag, the preview says why the node would refuse.
    expect(await within(dialog).findByText(/has 2 consumers attached/)).toBeInTheDocument();
    // The headline agrees with the row: a refused node is not one the delete would apply to.
    expect(within(dialog).getByText('Would apply to 0 of 1 nodes, 1 refused')).toBeInTheDocument();

    const disconnect = within(dialog).getByRole('checkbox', { name: /Disconnect this queue's consumers/ });
    disconnect.focus();
    await user.keyboard(' ');
    expect(disconnect).toBeChecked();

    // The preview is taken again with the flag, and names the divert that goes with the queue.
    expect(await within(dialog).findByText(/'feed' \(incoming → orders\.addr/)).toBeInTheDocument();
    expect(urls.at(-1)).toContain('disconnectConsumers=true');
    expect(urls.at(-1)).toContain('dryRun=true');

    within(dialog).getByRole('textbox').focus();
    await user.keyboard('orders');
    await user.tab();
    expect(within(dialog).getByRole('button', { name: 'Delete this queue' })).toHaveFocus();
    await user.keyboard('{Enter}');

    expect(await within(dialog).findByText('applied')).toBeInTheDocument();
    expect(urls.at(-1)).toContain('dryRun=false');
    expect(urls.at(-1)).toContain('disconnectConsumers=true');

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('keeps the consumer choice locked while the delete is in flight, so its result is never lost', async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/queues/orders', async ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        if (!dryRun) await gate;
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          partial: false,
          totalAffected: 12,
          nodes: [
            {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: dryRun ? 'WOULD_APPLY' : 'APPLIED',
              affected: 12,
              error: null,
            },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled(),
    );
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('would destroy 12 messages')).toBeInTheDocument();

    await user.type(within(dialog).getByRole('textbox'), 'orders');
    await user.click(within(dialog).getByRole('button', { name: 'Delete this queue' }));

    const disconnect = within(dialog).getByRole('checkbox', { name: /Disconnect this queue's consumers/ });
    await waitFor(() => expect(disconnect).toBeDisabled());
    await user.click(disconnect);

    release();
    expect(await within(dialog).findByText('applied')).toBeInTheDocument();
    expect(within(dialog).getByText('destroyed 12 messages')).toBeInTheDocument();
  });
});

describe('a delete that failed everywhere', () => {
  it('leaves the queue open instead of closing it as though the queue were gone', async () => {
    server.use(
      meHandler(),
      clusterHandler(AVAILABLE),
      http.delete('*/api/v1/clusters/c1/queues/orders', ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        return HttpResponse.json({
          dryRun,
          cap: 1000,
          overCap: false,
          // Every node failed, so the run is not partial — and the queue is still there.
          partial: false,
          totalAffected: dryRun ? 12 : 0,
          nodes: [
            {
              nodeId: 'n1',
              nodeName: 'node-a',
              status: dryRun ? 'WOULD_APPLY' : 'FAILED',
              affected: dryRun ? 12 : null,
              error: dryRun ? null : 'The broker did not answer in time.',
            },
          ],
        });
      }),
    );
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(<QueueLifecycleActions clusterId="c1" queue={queue()} onClose={onClose} />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Delete queue' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: 'Delete queue' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('would destroy 12 messages')).toBeInTheDocument();
    await user.type(within(dialog).getByRole('textbox'), 'orders');
    await user.click(within(dialog).getByRole('button', { name: 'Delete this queue' }));

    expect(await within(dialog).findByText('Failed on every node')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Close' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(onClose).not.toHaveBeenCalled();
  });
});
