import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { manifestHandler } from '../test/manifest.ts';
import { renderAppAt } from '../test/render.tsx';
import { server } from '../test/setup.ts';

const capability = { status: 'AVAILABLE', reason: null, brokerXmlSnippet: null };

/** What the shell reads on every cluster screen: the operator, the rail, the cluster's header and the palette. */
function mockShell() {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'operator',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json([{ id: 'c1', name: 'prod-eu', health: 'OK', nodeCount: 2 }]),
    ),
    http.get('*/api/v1/environments', () => HttpResponse.json([])),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json([])),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod-eu',
        topology: { nodes: [] },
        health: { level: 'OK', splitBrain: 'NONE', notes: [] },
        capabilities: {
          managementRead: capability,
          managementWrite: capability,
          messageIo: capability,
          notifications: capability,
        },
      }),
    ),
    http.get('*/api/v1/clusters/c1/queues', () =>
      HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 50 }),
    ),
  );
}

describe('the composed route tree', () => {
  it("reaches the page explaining a disabled feature at that feature's own address", async () => {
    mockShell();
    server.use(manifestHandler(['sql']));

    renderAppAt('/clusters/c1/sql');

    expect(await screen.findByRole('heading', { name: 'sql is disabled on this installation' })).toBeInTheDocument();
    expect(screen.getByText('artemis-studio.features.sql.enabled=true')).toBeInTheDocument();
    // The cluster's header, contributed by the clusters feature, still frames the page.
    expect(await screen.findByRole('heading', { name: 'prod-eu' })).toBeInTheDocument();
  });

  it('routes a page a feature adds outside any cluster', async () => {
    renderAppAt('/change-password');

    expect(await screen.findByRole('heading', { name: 'Change your password' })).toBeInTheDocument();
  });
});
