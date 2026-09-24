import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

const CLUSTER = 'c1';

function finding(over: Record<string, unknown> = {}) {
  return {
    code: 'HA_SINGLE_PAIR_QUORUM',
    category: 'HIGH_AVAILABILITY',
    severity: 'CRITICAL',
    subject: 'cluster',
    subjectLabel: 'cluster',
    title: 'A single replication pair cannot win a quorum vote, so a network partition splits the brain',
    impact: 'With one primary there is nobody to vote.',
    evidence: [{ node: 'primary', key: 'HAPolicy', value: 'Replication Primary w/quorum voting' }],
    recommendation: 'Coordinate the pair through a distributed lock manager.',
    snippet: '<ha-policy/>',
    caveats: ['network-check-list is not visible.'],
    appliable: false,
    firstSeenAt: '2026-09-24T10:00:00Z',
    lastSeenAt: '2026-09-24T10:00:00Z',
    stale: false,
    acceptance: null,
    ...over,
  };
}

function review(over: Record<string, unknown> = {}) {
  return {
    clusterId: CLUSTER,
    reviewedAt: new Date().toISOString(),
    durationMs: 120,
    nodesTotal: 2,
    nodesReviewed: 2,
    clusterEvaluated: true,
    nodes: [
      { nodeId: 'n1', nodeName: 'primary', live: true, reviewed: true, reason: null },
      { nodeId: 'n2', nodeName: 'backup', live: false, reviewed: true, reason: null },
    ],
    findings: [
      finding(),
      finding({
        code: 'MESSAGES_NO_EXPIRY_ADDRESS',
        category: 'MESSAGE_SAFETY',
        severity: 'INFO',
        subject: 'node:n1',
        subjectLabel: 'primary',
        title: 'No expiry address on primary: expired messages are discarded',
        appliable: true,
      }),
    ],
    notAssessed: [],
    open: { critical: 1, warning: 0, info: 1 },
    accepted: 0,
    rulesInCatalogue: 22,
    notice: null,
    ...over,
  };
}

const capability = { status: 'AVAILABLE', reason: null, brokerXmlSnippet: null };

/** What the shell itself reads on any cluster screen, plus this view's review. */
function shell(reviewBody: () => Record<string, unknown>) {
  server.use(
    http.get('*/api/v1/clusters', () => HttpResponse.json([{ id: CLUSTER, name: 'prod', health: 'OK', nodeCount: 2 }])),
    http.get('*/api/v1/environments', () => HttpResponse.json([])),
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ops',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json([])),
    http.get(`*/api/v1/clusters/${CLUSTER}/queues`, () => HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 50 })),
    http.get(`*/api/v1/clusters/${CLUSTER}/setup-review`, () => HttpResponse.json(reviewBody())),
    http.get(`*/api/v1/clusters/${CLUSTER}`, () =>
      HttpResponse.json({
        id: CLUSTER,
        name: 'prod',
        description: null,
        topology: { clusterId: CLUSTER, nodes: [], unmanaged: [] },
        health: { clusterId: CLUSTER, level: 'OK', splitBrain: 'NONE', replicationBehind: false, notes: [] },
        capabilities: {
          managementRead: capability,
          managementWrite: capability,
          messageIo: capability,
          notifications: capability,
        },
      }),
    ),
  );
}

describe('Setup review', () => {
  beforeEach(() => shell(() => review()));

  it('explains what it checks before the first review', async () => {
    shell(() => review({ reviewedAt: null, findings: [], nodes: [], nodesTotal: 0, nodesReviewed: 0 }));
    renderAppAt(`/clusters/${CLUSTER}/setup-review`);

    expect(await screen.findByText('Not reviewed yet')).toBeInTheDocument();
    expect(screen.getByText(/checking 22 rules/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Review now' })).toBeEnabled();
  });

  it('shows findings worst first, with severity in words, evidence and the fix', async () => {
    renderAppAt(`/clusters/${CLUSTER}/setup-review`);

    const card = await screen.findByRole('article', { name: /single replication pair/ });
    expect(within(card).getByText('Critical')).toBeInTheDocument();
    expect(within(card).getByRole('table', { name: 'Evidence for HA_SINGLE_PAIR_QUORUM' })).toHaveTextContent(
      'Replication Primary w/quorum voting',
    );
    expect(within(card).getByRole('button', { name: 'Copy the broker.xml fix for HA_SINGLE_PAIR_QUORUM' })).toBeInTheDocument();
    expect(within(card).getByText(/Note: network-check-list/)).toBeInTheDocument();
    expect(screen.getByText('Open: 1 critical, 0 warnings, 1 info.')).toBeInTheDocument();
    const info = screen.getByRole('article', { name: /No expiry address/ });
    expect(within(info).getByRole('link', { name: 'Apply it in Broker configuration' })).toHaveAttribute(
      'href',
      `/clusters/${CLUSTER}/configuration`,
    );
  });

  it('names a node it could not read rather than presenting it as healthy', async () => {
    shell(() =>
      review({
        nodesReviewed: 1,
        clusterEvaluated: false,
        nodes: [
          { nodeId: 'n1', nodeName: 'primary', live: true, reviewed: true, reason: null },
          { nodeId: 'n3', nodeName: 'other', live: true, reviewed: false, reason: 'Nothing answered at this address.' },
        ],
        findings: [],
        open: { critical: 0, warning: 0, info: 0 },
      }),
    );
    renderAppAt(`/clusters/${CLUSTER}/setup-review`);

    expect(await screen.findByText('1 node not reviewed')).toBeInTheDocument();
    expect(screen.getByText(/Nothing answered at this address/)).toBeInTheDocument();
    expect(screen.getByText(/Cluster-wide rules — quorum, version skew — were not evaluated/)).toBeInTheDocument();
    expect(screen.getByText(/That is not proof of a sound setup/)).toBeInTheDocument();
  });

  it('says a filter emptied the view and offers to clear it', async () => {
    const user = userEvent.setup();
    renderAppAt(`/clusters/${CLUSTER}/setup-review?severity=WARNING`);

    expect(await screen.findByText(/No findings match this filter/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear the filter' }));
    expect(await screen.findByRole('article', { name: /single replication pair/ })).toBeInTheDocument();
  });

  it('accepts a risk with a required reason, by keyboard, and returns focus', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(`*/api/v1/clusters/${CLUSTER}/setup-review/acceptances`, async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          review({
            findings: [
              finding({
                acceptance: {
                  reason: 'dev only',
                  acceptedBy: 'ops',
                  createdAt: new Date().toISOString(),
                  expiresAt: null,
                  active: true,
                },
              }),
            ],
            open: { critical: 0, warning: 0, info: 0 },
            accepted: 1,
          }),
        );
      }),
    );
    const user = userEvent.setup();
    renderAppAt(`/clusters/${CLUSTER}/setup-review`);

    const card = await screen.findByRole('article', { name: /single replication pair/ });
    const trigger = within(card).getByRole('button', { name: 'Accept as a known risk…' });
    await user.click(trigger);
    const dialog = await screen.findByRole('dialog', { name: 'Accept as a known risk' });

    await user.click(within(dialog).getByRole('button', { name: 'Accept the risk' }));
    expect(await within(dialog).findByText('A reason is required.')).toBeInTheDocument();
    expect(body).toBeNull();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());

    await user.click(trigger);
    const again = await screen.findByRole('dialog', { name: 'Accept as a known risk' });
    await user.type(within(again).getByLabelText(/^Reason/), 'dev only');
    await user.click(within(again).getByRole('button', { name: 'Accept the risk' }));

    const accepted = await screen.findByRole('article', { name: /single replication pair/ });
    await waitFor(() => expect(accepted).toHaveTextContent('Accepted as a known risk by ops'));
    expect(within(accepted).getByRole('button', { name: 'Revoke acceptance' })).toBeInTheDocument();
    expect(body).toMatchObject({ code: 'HA_SINGLE_PAIR_QUORUM', subject: 'cluster', reason: 'dev only' });
    expect((body as unknown as { expiresAt: string }).expiresAt).toBeTruthy();
    expect(screen.getByRole('status', { name: 'Setup review outcome' })).toHaveTextContent('HA_SINGLE_PAIR_QUORUM accepted as a known risk.');
  });

  it('reports a review that was too soon, in words', async () => {
    server.use(
      http.post(`*/api/v1/clusters/${CLUSTER}/setup-review/run`, () =>
        HttpResponse.json(review({ notice: 'Reviewed 3s ago; the next review can run in 27s. Showing the last review.' })),
      ),
    );
    const user = userEvent.setup();
    renderAppAt(`/clusters/${CLUSTER}/setup-review`);

    await user.click(await screen.findByRole('button', { name: 'Review now' }));
    expect(await screen.findByRole('status', { name: 'Setup review outcome' })).toHaveTextContent(/the next review can run in 27s/);
  });
});
