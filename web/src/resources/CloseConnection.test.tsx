import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import type { CapabilityView, ConnectionCloseView } from '../api/client.ts';
import { CloseConnectionAction } from './CloseConnection.tsx';

const AVAILABLE: CapabilityView = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function clusterHandler() {
  return http.get('*/api/v1/clusters/c1', () =>
    HttpResponse.json({
      id: 'c1',
      name: 'c1',
      description: null,
      topology: { nodes: [] },
      capabilities: {
        managementRead: AVAILABLE,
        managementWrite: AVAILABLE,
        notifications: AVAILABLE,
        messageIo: AVAILABLE,
        slowConsumerDetection: AVAILABLE,
      },
      health: { level: 'OK', reasons: [] },
      environmentId: null,
    }),
  );
}

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

const NODE = { nodeId: 'n1', nodeName: 'node-a' };

function outcome(status: string, affected: number | null, dryRun: boolean) {
  return {
    dryRun,
    cap: 0,
    overCap: false,
    partial: false,
    totalAffected: affected ?? 0,
    nodes: [{ ...NODE, status, affected, error: null }],
  };
}

/** A live target: the preview names the application and what it is holding. */
function live(dryRun: boolean): ConnectionCloseView {
  return {
    kind: 'CONNECTION',
    subject: 'a3f1c9de',
    alreadyGone: false,
    target: {
      connectionId: 'a3f1c9de',
      clientId: 'orders-worker-7',
      remoteAddress: '10.4.2.9:53160',
      user: 'apps',
      protocol: 'CORE',
      sessionCount: 2,
      consumerCount: 3,
      messagesInTransit: 41,
      confirmToken: 'orders-worker-7',
    },
    outcome: outcome(dryRun ? 'WOULD_APPLY' : 'APPLIED', 41, dryRun),
  } as ConnectionCloseView;
}

/** The row was stale: by the time the operator clicked, the connection had gone. */
function gone(dryRun: boolean): ConnectionCloseView {
  // No `target` at all — the broker had nothing to describe.
  return {
    kind: 'CONNECTION',
    subject: 'a3f1c9de',
    alreadyGone: true,
    outcome: outcome(dryRun ? 'WOULD_APPLY' : 'ALREADY', null, dryRun),
  } as unknown as ConnectionCloseView;
}

function closeHandler(reply: (dryRun: boolean) => ConnectionCloseView) {
  return http.post('*/api/v1/clusters/c1/nodes/n1/connections/a3f1c9de/close', ({ request }) =>
    HttpResponse.json(reply(new URL(request.url).searchParams.get('dryRun') === 'true')),
  );
}

function Harness() {
  return (
    <CloseConnectionAction
      clusterId="c1"
      kind="connection"
      nodeId="n1"
      nodeName="node-a"
      targetId="a3f1c9de"
      rowLabel="orders-worker-7"
      fetchedAt={Date.now() - 4_000}
    />
  );
}

describe('closing a connection from a row', () => {
  it('confirms against the client id, not the opaque connection id', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(live));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));

    const dialog = await screen.findByRole('dialog');
    // The typed token is the recognisable name; the connection id is not offered.
    await screen.findByRole('textbox', { name: /type "orders-worker-7" to confirm/i });
    expect(within(dialog).queryByLabelText(/type "a3f1c9de"/i)).toBeNull();
  });

  it('states how many in-flight messages return to their queue before it can be armed', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(live));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));

    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText(/41 in-flight messages will return to their queue/i);
    await within(dialog).findByText(/increased delivery count/i);
    // The scale of the disconnect, not just the one connection.
    expect(within(dialog).getByText('Sessions closed with it')).toBeInTheDocument();
    expect(within(dialog).getByText('Consumers closed with it')).toBeInTheDocument();
  });

  it('reports a target that has already gone as done, in the success position', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(gone));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));

    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText(/had already gone/i);
    // Not an error, and there is nothing left to confirm.
    expect(within(dialog).queryByRole('alert')).toBeNull();
    expect(within(dialog).queryByRole('textbox')).toBeNull();
  });

  it('is reachable and dismissable by keyboard alone, and returns focus to the trigger', async () => {
    server.use(meHandler(), clusterHandler(), closeHandler(live));
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const trigger = await screen.findByRole('button', { name: /close the connection for/i });
    trigger.focus();
    await user.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(trigger));
  });

  it('does not act until the exact client id has been typed', async () => {
    const closes = vi.fn();
    server.use(
      meHandler(),
      clusterHandler(),
      http.post('*/api/v1/clusters/c1/nodes/n1/connections/a3f1c9de/close', ({ request }) => {
        const dryRun = new URL(request.url).searchParams.get('dryRun') === 'true';
        if (!dryRun) closes();
        return HttpResponse.json(live(dryRun));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    await user.click(await screen.findByRole('button', { name: /close the connection for/i }));
    const field = await screen.findByRole('textbox', {
      name: /type "orders-worker-7" to confirm/i,
    });

    await user.type(field, 'orders-worker');
    expect(screen.getByRole('button', { name: /close this connection/i })).toBeDisabled();

    await user.type(field, '-7');
    await user.click(screen.getByRole('button', { name: /close this connection/i }));
    await waitFor(() => expect(closes).toHaveBeenCalledTimes(1));
  });
});
