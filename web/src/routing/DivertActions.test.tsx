import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import type { CapabilityView, DivertView } from '../api/client.ts';
import { CreateDivertAction, DeleteDivertAction } from './DivertActions.tsx';

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

function outcome(dryRun: boolean) {
  return {
    dryRun,
    cap: 0,
    overCap: false,
    totalAffected: 0,
    anyFailed: false,
    nodes: [
      {
        nodeId: 'n1',
        nodeName: 'node-a',
        status: dryRun ? 'WOULD_APPLY' : 'APPLIED',
        affected: null,
        error: null,
      },
    ],
  };
}

const BROKER_XML = '<diverts>\n  <divert name="audit-copy">\n  </divert>\n</diverts>\n';

const DIVERT: DivertView = {
  name: 'audit-copy',
  routingName: 'audit-copy',
  address: 'ORDER.IN',
  forwardingAddress: 'AUDIT.IN',
  filter: null,
  routingType: null,
  transformerClassName: null,
  exclusive: false,
  retroactiveResource: false,
  owner: 'OPERATOR',
  captureSubscriptionId: null,
  nodesPresent: 1,
  nodesTotal: 1,
  perNode: [{ nodeId: 'n1', nodeName: 'node-a' }],
};

describe('creating a divert', () => {
  it('states the configuration drift and shows the broker.xml before the create is offered', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.post('*/api/v1/clusters/c1/diverts', () =>
        HttpResponse.json({ outcome: outcome(true), brokerXml: BROKER_XML }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<CreateDivertAction clusterId="c1" />);

    await user.click(await screen.findByRole('button', { name: 'Create divert' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'audit-copy');
    await user.type(screen.getByRole('textbox', { name: /Divert messages from/ }), 'ORDER.IN');
    await user.type(screen.getByRole('textbox', { name: /Divert messages to/ }), 'AUDIT.IN');
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    // The consequence is stated with the action, not behind a dismissible notice,
    // and it is the drift consequence — not a claim that the divert is temporary.
    expect(await screen.findByText(/stays on the broker across restarts/)).toBeInTheDocument();
    expect(screen.queryByText(/lost when the broker restarts/)).not.toBeInTheDocument();
    expect(screen.getByText(/Add this to broker.xml/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Copy' })).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Create on every live node' }),
    ).toBeInTheDocument();
  });
});

describe('deleting a divert', () => {
  it('is not offered for a divert message capture owns', async () => {
    server.use(clusterHandler(), meHandler());
    renderWithProviders(
      <DeleteDivertAction clusterId="c1" divert={{ ...DIVERT, owner: 'MESSAGE_CAPTURE' }} />,
    );
    expect(await screen.findByText('Owned by message capture')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('previews per node and arms only on the divert name typed exactly', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.delete('*/api/v1/clusters/c1/diverts/audit-copy', ({ request }) =>
        HttpResponse.json(outcome(new URL(request.url).searchParams.get('dryRun') === 'true')),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DeleteDivertAction clusterId="c1" divert={DIVERT} />);

    await user.click(await screen.findByRole('button', { name: 'Delete' }));
    expect(await screen.findByText(/AUDIT.IN stops receiving a copy/)).toBeInTheDocument();

    const confirm = screen.getByRole('button', { name: 'Delete on every live node' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: /audit-copy/ }), 'audit-cop');
    expect(confirm).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: /audit-copy/ }), 'y');
    await waitFor(() => expect(confirm).toBeEnabled());
  });

  it('can be dismissed with the keyboard, returning focus to the trigger', async () => {
    server.use(
      clusterHandler(),
      meHandler(),
      http.delete('*/api/v1/clusters/c1/diverts/audit-copy', () => HttpResponse.json(outcome(true))),
    );
    const user = userEvent.setup();
    renderWithProviders(<DeleteDivertAction clusterId="c1" divert={DIVERT} />);

    const trigger = await screen.findByRole('button', { name: 'Delete' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(dialog).toContainElement(document.activeElement as HTMLElement | null));

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
