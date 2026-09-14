import { IconListDetails } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { validateResourceSearch } from '../../kernel/routing/search.ts';
import { keys } from './api.ts';
import { QueuePalette } from './QueuePalette.tsx';
import { QueuesView } from './QueuesView.tsx';

const queuesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'queues',
  component: featureView('queues', QueuesView),
  validateSearch: validateResourceSearch,
});

/** Queues across the cluster, and their lifecycle: create, edit, pause, delete. */
export const queuesFeature = defineFeature({
  contract: CONTRACT,
  id: 'queues',
  routes: { cluster: [queuesRoute] },
  nav: [
    { group: 'messaging', order: 10, label: 'Queues', icon: IconListDetails, path: 'queues', permission: 'cluster:read' },
  ],
  palette: QueuePalette,
  streamTopics: {
    queues: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'queues')),
  },
});
