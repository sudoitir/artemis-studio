import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { StudioHealth } from './StudioHealth.tsx';

const NOW = new Date().toISOString();

const JOB = { name: 'scrape.tier-a', feature: 'scrape', status: 'OK', lastEnd: NOW, lagSeconds: 0, degraded: false };
const NODE = {
  name: 'broker-0',
  clusterId: '00000000-0000-0000-0000-000000000001',
  node: 'broker-0:8161',
  lastSuccess: NOW,
  lastFailure: null,
  lastError: null,
  managementP95Millis: 12.4,
  rateLimitWaitMillis: 0,
  degraded: false,
};

const SELF = {
  id: '11111111-aaaa-bbbb-cccc-000000000001',
  host: 'studio-1',
  version: '2026.09.70',
  state: 'READY',
  startedAt: NOW,
  heartbeatAgeMillis: 2_000,
  ownedClusters: [{ id: '00000000-0000-0000-0000-000000000001', name: 'orders' }],
  self: true,
  degraded: false,
};

function health(over: Record<string, unknown> = {}) {
  return http.get('*/api/v1/system/health', () =>
    HttpResponse.json({
      jobs: [JOB],
      nodes: [NODE],
      replicas: [SELF],
      answeringReplica: SELF.id,
      dbPool: { active: 2, idle: 8, max: 10, pending: 0 },
      streamClients: 3,
      degraded: false,
      ...over,
    }),
  );
}

describe('StudioHealth', () => {
  it('shows a healthy Studio in words', async () => {
    server.use(health());
    renderWithProviders(<StudioHealth />);

    const jobs = await screen.findByRole('table', { name: 'Background jobs' });
    expect(within(jobs).getByText('scrape.tier-a')).toBeInTheDocument();
    expect(within(jobs).getByText('On schedule')).toBeInTheDocument();
    expect(within(jobs).getByText('Healthy')).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Broker nodes' })).getByText('12 ms')).toBeInTheDocument();
    expect(screen.getByText('Open event streams').closest('dl')).toHaveTextContent('3');
    expect(screen.queryByText('Degraded')).not.toBeInTheDocument();
  });

  it('marks the lagging job and the view as degraded', async () => {
    server.use(
      health({
        jobs: [{ ...JOB, lagSeconds: 125, degraded: true }],
        nodes: [{ ...NODE, lastFailure: NOW, lastError: 'connection refused', degraded: true }],
        degraded: true,
      }),
    );
    renderWithProviders(<StudioHealth />);

    const jobs = await screen.findByRole('table', { name: 'Background jobs' });
    expect(within(jobs).getByText('2m behind')).toBeInTheDocument();
    expect(within(jobs).getByText('Degraded')).toBeInTheDocument();
    const nodes = screen.getByRole('table', { name: 'Broker nodes' });
    expect(within(nodes).getByText('Degraded')).toBeInTheDocument();
    expect(within(nodes).getByText('connection refused')).toBeInTheDocument();
    expect(screen.getAllByText('Degraded')).toHaveLength(3);
  });

  it('lists the replicas with their clusters and marks the one answering', async () => {
    server.use(
      health({
        replicas: [
          SELF,
          {
            ...SELF,
            id: '22222222-aaaa-bbbb-cccc-000000000002',
            host: 'studio-2',
            heartbeatAgeMillis: 4_000,
            ownedClusters: [],
            self: false,
          },
        ],
      }),
    );
    renderWithProviders(<StudioHealth />);

    const replicas = await screen.findByRole('table', { name: 'Replicas' });
    const first = within(replicas).getByRole('row', { name: /studio-1/ });
    expect(first).toHaveTextContent('This replica');
    expect(first).toHaveTextContent('2026.09.70');
    expect(first).toHaveTextContent('Ready');
    expect(first).toHaveTextContent('2s ago');
    expect(first).toHaveTextContent('orders');
    const second = within(replicas).getByRole('row', { name: /studio-2/ });
    expect(second).not.toHaveTextContent('This replica');
    expect(second).toHaveTextContent('None');
    expect(screen.getByText(/as seen from this replica \(studio-1\)/)).toBeInTheDocument();
  });

  it('marks a replica that is gone or draining in words', async () => {
    server.use(
      health({
        replicas: [
          SELF,
          {
            ...SELF,
            id: '22222222-aaaa-bbbb-cccc-000000000002',
            host: 'studio-2',
            state: 'GONE',
            heartbeatAgeMillis: 125_000,
            ownedClusters: [],
            self: false,
            degraded: true,
          },
          {
            ...SELF,
            id: '33333333-aaaa-bbbb-cccc-000000000003',
            host: 'studio-3',
            state: 'DRAINING',
            ownedClusters: [],
            self: false,
            degraded: true,
          },
          {
            ...SELF,
            id: '44444444-aaaa-bbbb-cccc-000000000004',
            host: 'studio-4',
            state: 'STOPPED',
            ownedClusters: [],
            self: false,
          },
        ],
        degraded: true,
      }),
    );
    renderWithProviders(<StudioHealth />);

    const replicas = await screen.findByRole('table', { name: 'Replicas' });
    const gone = within(replicas).getByRole('row', { name: /studio-2/ });
    expect(gone).toHaveTextContent('GoneNo heartbeat; presumed crashed');
    expect(gone).toHaveTextContent('2m ago');
    expect(gone).toHaveTextContent('Degraded');
    const draining = within(replicas).getByRole('row', { name: /studio-3/ });
    expect(draining).toHaveTextContent('Draining');
    expect(draining).toHaveTextContent('Degraded');
    const stopped = within(replicas).getByRole('row', { name: /studio-4/ });
    expect(stopped).toHaveTextContent('Stopped');
    expect(stopped).not.toHaveTextContent('Degraded');
    expect(within(replicas).getByRole('row', { name: /studio-1/ })).toHaveTextContent('Healthy');
  });

  it('says a figure is unavailable instead of showing zero', async () => {
    server.use(
      health({
        jobs: [{ ...JOB, lagSeconds: null }],
        nodes: [{ ...NODE, lastSuccess: null, managementP95Millis: null, rateLimitWaitMillis: null }],
        dbPool: { active: null, idle: null, max: null, pending: null },
        streamClients: null,
      }),
    );
    renderWithProviders(<StudioHealth />);

    await screen.findByRole('table', { name: 'Background jobs' });
    expect(screen.getAllByText('Unavailable')).toHaveLength(8);
    expect(screen.getByText('Open event streams').closest('dl')).not.toHaveTextContent('0');
    expect(screen.getByText('Database connections in use').closest('dl')).not.toHaveTextContent('0');
    // Each one says why it is missing, so an absent reading is not taken for a fact.
    expect(screen.getAllByText('The server could not read it.')).toHaveLength(5);
  });

  it('teaches when no jobs or nodes are registered', async () => {
    server.use(health({ jobs: [], nodes: [] }));
    renderWithProviders(<StudioHealth />);

    expect(await screen.findByText(/No background jobs are registered/)).toBeInTheDocument();
    expect(screen.getByText(/No broker nodes are registered/)).toBeInTheDocument();
  });

  it('states the cause and offers a retry when the read fails', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/system/health', () => {
        calls++;
        return calls === 1
          ? HttpResponse.json({ title: 'Boom', status: 500, detail: 'The database is down.' }, { status: 500 })
          : HttpResponse.json({
              jobs: [],
              nodes: [],
              replicas: [],
              answeringReplica: null,
              dbPool: { active: 0, idle: 0, max: 10, pending: 0 },
              streamClients: 0,
              degraded: false,
            });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<StudioHealth />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Studio failed to complete the request');
    expect(alert).toHaveTextContent('The database is down.');
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('No background jobs are registered')).toBeInTheDocument();
  });

  it('names the permission when the read is forbidden', async () => {
    server.use(
      http.get('*/api/v1/system/health', () =>
        HttpResponse.json(
          { title: 'Forbidden', status: 403, detail: 'Denied.', permission: 'settings:read' },
          { status: 403 },
        ),
      ),
    );
    renderWithProviders(<StudioHealth />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Your role does not include the settings:read permission.');
    expect(alert).toHaveTextContent('Ask an administrator to grant settings:read.');
  });

  it('shows a loading state before the first answer', () => {
    server.use(http.get('*/api/v1/system/health', () => new Promise(() => {})));
    renderWithProviders(<StudioHealth />);

    const loading = screen.getByRole('status');
    expect(loading).toHaveTextContent('Loading Studio health');
    expect(loading).toHaveAttribute('aria-busy', 'true');
  });
});
