import { IconActivityHeartbeat } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { validateResourceSearch } from '../../kernel/routing/search.ts';
import { keys } from './api.ts';
import { ConsumerHealthView } from './ConsumerHealthView.tsx';
import { QueueHealthPanel } from './QueueHealthPanel.tsx';

const consumerHealthRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'consumer-health',
  component: featureView('triage', ConsumerHealthView),
  validateSearch: validateResourceSearch,
});

/**
 * Consumer health: every queue ranked by whether its consumers are keeping up, and
 * the same verdict in a queue's detail drawer (ADR-0089).
 *
 * Its id is `triage`, the backend module that owns the verdict — the nav label is
 * what an operator calls the screen, the id is what the manifest enables.
 */
export const triageFeature = defineFeature({
  contract: CONTRACT,
  id: 'triage',
  routes: { cluster: [consumerHealthRoute] },
  nav: [
    {
      group: 'observe',
      // Between Flow (15) and Metrics (20): the question it answers comes after
      // "where does this go" and before "what do the charts say".
      order: 18,
      label: 'Consumer health',
      icon: IconActivityHeartbeat,
      path: 'consumer-health',
      permission: 'cluster:read',
    },
  ],
  slots: {
    // Above the metrics history panel (order 10): the verdict first, its charts after.
    'queue.detail.panels': [{ id: 'triage-queue-health', order: 5, Component: QueueHealthPanel }],
  },
  streamTopics: {
    // A verdict is derived from the sweep, so it changes when the sweep lands.
    queues: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId)),
  },
});
