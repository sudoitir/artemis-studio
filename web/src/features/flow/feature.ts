import { IconChartSankey } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { FlowView } from './FlowView.tsx';
import { validateFlowSearch } from './flowSearch.ts';
import { AddressInFlow, ConnectionInFlow, DivertInFlow, QueueInFlow } from './rowActions.tsx';

const flowRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'flow',
  component: featureView('flow', FlowView),
  validateSearch: validateFlowSearch,
});

/** Client connectivity and message flow: who sends where, how it routes, who consumes it. */
export const flowFeature = defineFeature({
  contract: CONTRACT,
  id: 'flow',
  routes: { cluster: [flowRoute] },
  nav: [{ group: 'observe', order: 15, label: 'Flow', icon: IconChartSankey, path: 'flow', hotkey: 'f', permission: 'cluster:read' }],
  slots: {
    'queue.actions': [{ id: 'flow.queue', order: 40, section: 'open', Component: QueueInFlow }],
    'address.actions': [{ id: 'flow.address', order: 40, section: 'open', Component: AddressInFlow }],
    'connection.actions': [{ id: 'flow.connection', order: 40, section: 'open', Component: ConnectionInFlow }],
    'divert.actions': [{ id: 'flow.divert', order: 40, section: 'open', Component: DivertInFlow }],
  },
  streamTopics: {
    flow: ({ clusterId, invalidate }) => invalidate(['clusters', clusterId, 'flow']),
  },
});
