import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { TracingDiagnostics } from './TracingDiagnostics.tsx';

function diagnostics(over: Record<string, unknown> = {}) {
  return {
    asOf: '2026-09-06T12:00:00Z',
    sampleIntervalMs: 5_000,
    nodesWithCoreEndpoint: 2,
    nodesTotal: 2,
    notificationsCapability: 'CONNECTED',
    clock: {
      verdict: 'IN_AGREEMENT',
      worstOffsetMs: null,
      uncertaintyMs: null,
      skewedNodes: [],
      measuredAt: '2026-09-06T12:00:00Z',
      toleranceMs: 2_000,
    },
    expectations: [
      {
        expectationId: 'e1',
        requestAddress: 'orders.request',
        enabled: true,
        lastAttemptAt: '2026-09-06T12:00:00Z',
        lastSuccessAt: '2026-09-06T12:00:00Z',
        nodesSampled: 2,
        nodesTotal: 2,
        skipped: [],
        messagesBrowsed: 0,
        observations: 0,
        lastError: null,
        lastErrorAt: null,
        samplePerMin: 10,
        rateExceedsInterval: false,
      },
    ],
    reasons: [
      {
        code: 'CONSUMED_FASTER_THAN_SAMPLED',
        summary: 'Tracing browses every 5000ms, so a request consumed faster than that is never seen.',
        remedy: 'Raise samples per minute and lower rr.sample-interval.',
      },
    ],
    ...over,
  };
}

describe('TracingDiagnostics', () => {
  it('explains an empty Flows tab instead of leaving it blank', async () => {
    // "0 flows" reads identically whether nothing was sent, nothing could be
    // browsed, or the requests were consumed faster than the sampler ticks.
    server.use(
      http.get('*/api/v1/clusters/c1/rr/diagnostics', () => HttpResponse.json(diagnostics())),
    );
    renderWithProviders(<TracingDiagnostics clusterId="c1" />);

    expect(await screen.findByText(/consumed faster than that is never seen/)).toBeInTheDocument();
    // The remedy travels with the reason; a diagnosis with no next step is half an answer.
    expect(screen.getByText(/Raise samples per minute/)).toBeInTheDocument();
  });

  it('names the nodes that could not be sampled, and why', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/diagnostics', () =>
        HttpResponse.json(
          diagnostics({
            expectations: [
              {
                ...diagnostics().expectations[0],
                nodesSampled: 1,
                skipped: ["broker-1: no queue for address 'orders.request' in the last scrape"],
              },
            ],
          }),
        ),
      ),
    );
    renderWithProviders(<TracingDiagnostics clusterId="c1" />);

    expect(await screen.findByText(/no queue for address/)).toBeInTheDocument();
  });

  it('says when Studio itself is the suspect rather than blaming every broker', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/rr/diagnostics', () =>
        HttpResponse.json(
          diagnostics({
            clock: {
              verdict: 'STUDIO_SUSPECT',
              worstOffsetMs: 600_000,
              uncertaintyMs: 510,
              skewedNodes: ['broker-1', 'broker-2'],
              measuredAt: '2026-09-06T12:00:00Z',
              toleranceMs: 2_000,
            },
          }),
        ),
      ),
    );
    renderWithProviders(<TracingDiagnostics clusterId="c1" />);

    expect(await screen.findByText(/Studio.s own host/)).toBeInTheDocument();
  });
});
