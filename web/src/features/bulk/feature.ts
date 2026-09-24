import { IconStack2 } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { keys } from './api.ts';
import { BulkActionBar } from './BulkActionBar.tsx';
import { BulkRunsView } from './BulkRunsView.tsx';
import { BulkRunView } from './BulkRunView.tsx';
import { PurgeQueue } from './rowActions.tsx';

const runsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'bulk',
  component: featureView('bulk', BulkRunsView),
});

const runRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'bulk/$runId',
  component: featureView('bulk', BulkRunView),
});

/** Pause, resume, purge or delete many queues as one previewed, persisted run (ADR-0093). */
export const bulkFeature = defineFeature({
  contract: CONTRACT,
  id: 'bulk',
  routes: { cluster: [runsRoute, runRoute] },
  nav: [
    { group: 'activity', order: 15, label: 'Bulk runs', icon: IconStack2, path: 'bulk', hotkey: 'b', permission: 'cluster:read' },
  ],
  slots: {
    'queue.actions': [{ id: 'bulk.purge', order: 5, section: 'destroy', Component: PurgeQueue }],
    'queues.selection': [{ id: 'bulk-actions', order: 10, Component: BulkActionBar }],
  },
  streamTopics: {
    bulk: ({ clusterId, invalidate }) => invalidate(keys.all(clusterId)),
  },
});
