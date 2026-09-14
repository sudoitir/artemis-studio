import { IconChartLine } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { MetricsView } from './MetricsView.tsx';
import { QueueHistoryPanels } from './QueueHistoryPanels.tsx';
import { METRIC_RANGES, type MetricRange } from './ranges.ts';

export interface MetricsSearch {
  range?: MetricRange;
  from?: string;
  to?: string;
  /** Queue name to scope the series to; absent means cluster-wide. */
  subject?: string;
}

function validateMetricsSearch(raw: Record<string, unknown>): MetricsSearch {
  const out: MetricsSearch = {};
  // The scope survives a range change and an absolute window alike, so it is read
  // before either branch returns.
  if (typeof raw.subject === 'string' && raw.subject) out.subject = raw.subject;
  if (typeof raw.from === 'string' && raw.from && typeof raw.to === 'string' && raw.to) {
    out.from = raw.from;
    out.to = raw.to;
    return out;
  }
  if (typeof raw.range === 'string' && (METRIC_RANGES as readonly string[]).includes(raw.range)) {
    out.range = raw.range as MetricRange;
  }
  return out;
}

const metricsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'metrics',
  component: featureView('metrics', MetricsView),
  validateSearch: validateMetricsSearch,
});

/** Time series of depth, throughput and consumers, cluster-wide and per queue. */
export const metricsFeature = defineFeature({
  contract: CONTRACT,
  id: 'metrics',
  routes: { cluster: [metricsRoute] },
  nav: [
    { group: 'observe', order: 20, label: 'Metrics', icon: IconChartLine, path: 'metrics', permission: 'cluster:read' },
  ],
  slots: {
    'queue.detail.panels': [{ id: 'metrics-queue-history', order: 10, Component: QueueHistoryPanels }],
  },
});
