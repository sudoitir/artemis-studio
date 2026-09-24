import { IconListDetails } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { validateResourceSearch, type ResourceSearch } from '../../kernel/routing/search.ts';
import { keys } from './api.ts';
import {
  CopyQueueLink,
  CopyQueueName,
  DeleteQueue,
  EditQueue,
  OpenQueue,
  PauseResumeQueue,
  QueueLink,
} from './QueueActions.tsx';
import { QueuePalette } from './QueuePalette.tsx';
import { QueuesView } from './QueuesView.tsx';

/** The listing's own state, plus the queue whose detail is open — a shareable address. */
export interface QueuesSearch extends ResourceSearch {
  queue?: string;
}

function validateQueuesSearch(raw: Record<string, unknown>): QueuesSearch {
  const out: QueuesSearch = validateResourceSearch(raw);
  if (typeof raw.queue === 'string' && raw.queue) out.queue = raw.queue;
  return out;
}

const queuesRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'queues',
  component: featureView('queues', QueuesView),
  validateSearch: validateQueuesSearch,
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
  slots: {
    'queue.actions': [
      { id: 'queues.open', order: 10, section: 'open', Component: OpenQueue },
      { id: 'queues.copy-name', order: 10, section: 'copy', Component: CopyQueueName },
      { id: 'queues.copy-link', order: 20, section: 'copy', Component: CopyQueueLink },
      { id: 'queues.pause', order: 10, section: 'operate', Component: PauseResumeQueue },
      { id: 'queues.edit', order: 20, section: 'operate', Component: EditQueue },
      { id: 'queues.delete', order: 10, section: 'destroy', Component: DeleteQueue },
    ],
    'queue.link': [{ id: 'queues.link', order: 10, Component: QueueLink }],
  },
  streamTopics: {
    queues: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'queues')),
  },
});
