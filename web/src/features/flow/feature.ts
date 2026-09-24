import { IconChartSankey } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { FlowView } from './FlowView.tsx';
import { validateFlowSearch } from './flowSearch.ts';
import {
  AddressInFlow,
  ClientConnections,
  ConnectionInFlow,
  DivertInFlow,
  FocusClient,
  QueueInFlow,
} from './rowActions.tsx';

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
    'client.actions': [
      { id: 'flow.client.focus', order: 10, section: 'open', Component: FocusClient },
      { id: 'flow.client.connections', order: 20, section: 'open', Component: ClientConnections },
    ],
  },
  streamTopics: {
    flow: ({ clusterId, invalidate }) => invalidate(['clusters', clusterId, 'flow']),
  },
});
