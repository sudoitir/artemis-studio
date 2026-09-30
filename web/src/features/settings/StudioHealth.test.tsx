import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';

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

function health(over: Record<string, unknown> = {}) {
  return http.get('*/api/v1/system/health', () =>
    HttpResponse.json({
      jobs: [JOB],
      nodes: [NODE],
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
    expect(screen.getByRole('row', { name: /Open event streams/ })).toHaveTextContent('3');
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
    expect(screen.getByRole('row', { name: /Open event streams/ })).not.toHaveTextContent('0');
    expect(screen.getByRole('row', { name: /Database connections in use/ })).not.toHaveTextContent('0');
  });

  it('teaches when no jobs or nodes are registered', async () => {
    server.use(health({ jobs: [], nodes: [] }));
    renderWithProviders(<StudioHealth />);

    expect(await screen.findByText(/No background jobs are registered/)).toBeInTheDocument();
    expect(screen.getByText(/No broker nodes are registered/)).toBeInTheDocument();
  });

  it('states the cause and offers a retry when the read fails', async () => {
    server.use(
      http.get('*/api/v1/system/health', () =>
        HttpResponse.json(
          { title: 'Forbidden', status: 403, detail: 'You need the settings-read permission.' },
          { status: 403 },
        ),
      ),
    );
    renderWithProviders(<StudioHealth />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('could not be loaded');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('shows a loading state before the first answer', () => {
    server.use(http.get('*/api/v1/system/health', () => new Promise(() => {})));
    renderWithProviders(<StudioHealth />);

    expect(screen.getByLabelText('Loading Studio health')).toHaveAttribute('aria-busy', 'true');
  });
});
