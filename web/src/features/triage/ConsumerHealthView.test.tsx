import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

let currentSearch: Record<string, unknown> = {};
const navigateSpy = vi.fn();

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => currentSearch,
  useNavigate: () => navigateSpy,
}));

const { ConsumerHealthView } = await import('./ConsumerHealthView.tsx');

function row(over: Record<string, unknown> = {}) {
  return {
    address: 'orders',
    queueName: 'orders',
    verdict: 'HEALTHY',
    severity: 0,
    cause: 'Keeping up with what arrives.',
    source: 'DERIVED',
    brokerConsumerName: null,
    depth: 0,
    consumers: 3,
    delivering: 0,
    scheduled: 0,
    paused: false,
    depthSlopePerSecond: 0,
    addRate: 1,
    ackRate: 1,
    netRate: 0,
    ackRatePerConsumer: 0.33,
    drainEtaSeconds: null,
    asOf: '2026-09-20T10:00:00Z',
    sampleSpanSeconds: 30,
    stale: false,
    nodesPresent: 1,
    nodesTotal: 1,
    ...over,
  };
}

function page(rows: unknown[]) {
  return { data: rows, count: rows.length, page: 1, pageSize: 200 };
}

function withCluster() {
  server.use(
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({ topology: { nodes: [] } }),
    ),
  );
}

describe('ConsumerHealthView', () => {
  it('names each verdict in words, so colour is never the only signal', async () => {
    currentSearch = {};
    withCluster();
    server.use(
      http.get('*/api/v1/clusters/c1/consumer-health', () =>
        HttpResponse.json(
          page([
            row({ queueName: 'broken', verdict: 'NO_CONSUMERS', severity: 4, consumers: 0, depth: 9000 }),
            row({ queueName: 'stuck', verdict: 'STALLED', severity: 4, delivering: 30, depth: 5000 }),
          ]),
        ),
      ),
    );

    renderWithProviders(<ConsumerHealthView />);

    expect(await screen.findByText('No consumers')).toBeInTheDocument();
    expect(screen.getByText('Stalled')).toBeInTheDocument();
  });

  it('distinguishes an unmeasured queue from a healthy one', async () => {
    currentSearch = {};
    withCluster();
    server.use(
      http.get('*/api/v1/clusters/c1/consumer-health', () =>
        HttpResponse.json(
          page([
            row({ queueName: 'new-queue', verdict: 'INSUFFICIENT_DATA', severity: 0, ackRate: null, addRate: null }),
          ]),
        ),
      ),
    );

    renderWithProviders(<ConsumerHealthView />);

    // Stated as unmeasured, and never as healthy.
    expect(await screen.findByText('Not measured')).toBeInTheDocument();
    expect(screen.queryByText('Healthy')).not.toBeInTheDocument();
  });

  it('states an unmeasured rate rather than rendering it as zero', async () => {
    currentSearch = {};
    withCluster();
    server.use(
      http.get('*/api/v1/clusters/c1/consumer-health', () =>
        HttpResponse.json(page([row({ queueName: 'quiet', ackRate: null, depthSlopePerSecond: null })])),
      ),
    );

    renderWithProviders(<ConsumerHealthView />);

    // "0.00 msg/s" would read as "throughput stopped", which is a different fact.
    expect(await screen.findByText('not measured')).toBeInTheDocument();
    expect(screen.getByText('trend not measured')).toBeInTheDocument();
  });

  it('says a node was unreachable rather than presenting an absence as health', async () => {
    currentSearch = {};
    server.use(
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({
          topology: { nodes: [{ endpoints: [{ name: 'node-2', lastError: 'connection refused' }] }] },
        }),
      ),
      http.get('*/api/v1/clusters/c1/consumer-health', () => HttpResponse.json(page([]))),
    );

    renderWithProviders(<ConsumerHealthView />);

    expect(await screen.findByText(/node-2 could not be reached/)).toBeInTheDocument();
    expect(screen.getByText(/incomplete view rather than a healthy cluster/)).toBeInTheDocument();
  });

  it('reports a failed read rather than an empty cluster', async () => {
    currentSearch = {};
    withCluster();
    server.use(
      http.get('*/api/v1/clusters/c1/consumer-health', () =>
        HttpResponse.json({ title: 'Upstream unavailable', detail: 'the database did not answer' }, { status: 502 }),
      ),
    );

    renderWithProviders(<ConsumerHealthView />);

    expect(await screen.findByText(/did not answer/)).toBeInTheDocument();
  });

  it('offers to clear a filter that matched nothing', async () => {
    currentSearch = { q: 'nope' };
    withCluster();
    server.use(
      http.get('*/api/v1/clusters/c1/consumer-health', () => HttpResponse.json(page([]))),
    );

    renderWithProviders(<ConsumerHealthView />);

    expect(await screen.findByText(/No queue matches "nope"/)).toBeInTheDocument();
  });
});
