import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { holding } from '../../test/access.ts';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';
import { ClusterHeader } from './ClusterHeader.tsx';

const capability = { status: 'AVAILABLE', reason: null, brokerXmlSnippet: null };

function mockCluster(over: object = {}) {
  server.use(
    holding('environment:read'),
    http.get('*/api/v1/environments', () =>
      HttpResponse.json(paged([{ id: 'e1', name: 'Production', colour: '#d6336c', sortOrder: 1 }])),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({
        id: 'c1',
        name: 'prod-eu',
        environmentId: 'e1',
        topology: { nodes: [{ endpoints: [{}, {}] }] },
        health: { level: 'OK', splitBrain: 'NONE', notes: [] },
        capabilities: {
          managementRead: capability,
          managementWrite: capability,
          messageIo: capability,
          notifications: capability,
        },
        ...over,
      }),
    ),
  );
}

describe('ClusterHeader', () => {
  it('is a context strip with no heading: the cluster, its environment and what is known of it', async () => {
    mockCluster();
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    const strip = await screen.findByRole('group', { name: 'Cluster prod-eu' });
    expect(strip).toHaveTextContent('prod-eu');
    await waitFor(() => expect(strip).toHaveTextContent('Production'));
    expect(strip).toHaveTextContent('2 nodes · replication · reachable');
    // Each view's page header is the page's one h1.
    expect(screen.queryAllByRole('heading')).toHaveLength(0);
  });

  it('leaves the environment out for a cluster that has none', async () => {
    mockCluster({ environmentId: null });
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    const strip = await screen.findByRole('group', { name: 'Cluster prod-eu' });
    expect(strip).not.toHaveTextContent('Production');
  });

  it('announces a split brain as an alert and lists what is wrong', async () => {
    mockCluster({ health: { level: 'CRITICAL', splitBrain: 'CRITICAL', notes: ['Both nodes are serving'] } });
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Two nodes are live in one pair');
    expect(alert).toHaveTextContent('Both nodes are serving');
  });

  it('names its cause when the cluster cannot be read', async () => {
    server.use(
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/clusters/c1', () => HttpResponse.json({ title: 'Forbidden', status: 403 }, { status: 403 })),
    );
    renderWithProviders(<ClusterHeader clusterId="c1" />);

    expect(await screen.findByRole('alert')).toHaveTextContent('You are not allowed to do this');
  });
});
