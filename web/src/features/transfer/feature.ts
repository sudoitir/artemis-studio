import { IconTransfer } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { keys } from './api.ts';
import { TransferActions } from './TransferActions.tsx';
import { TransferRunView } from './TransferRunView.tsx';
import { TransfersView } from './TransfersView.tsx';
import { TransferQueueMessages } from './rowActions.tsx';

const runsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'transfers',
  component: featureView('transfer', TransfersView),
});

const runRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'transfers/$runId',
  component: featureView('transfer', TransferRunView),
});

/** Move or copy messages to a queue on another node or another cluster, as a resumable run (ADR-0097). */
export const transferFeature = defineFeature({
  contract: CONTRACT,
  id: 'transfer',
  routes: { cluster: [runsRoute, runRoute] },
  nav: [
    {
      group: 'messaging',
      order: 30,
      label: 'Transfers',
      icon: IconTransfer,
      path: 'transfers',
      hotkey: 'x',
      permission: 'message:read',
    },
  ],
  slots: {
    'queue.actions': [{ id: 'transfer.queue', order: 30, section: 'operate', Component: TransferQueueMessages }],
    'messages.selection': [{ id: 'transfer-actions', order: 10, Component: TransferActions }],
  },
  streamTopics: {
    transfer: ({ clusterId, invalidate }) => invalidate(keys.all(clusterId)),
  },
});
