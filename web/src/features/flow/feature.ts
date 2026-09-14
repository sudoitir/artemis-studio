import { IconChartSankey } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { FlowView } from './FlowView.tsx';
import { validateFlowSearch } from './flowSearch.ts';

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
  nav: [{ group: 'observe', order: 15, label: 'Flow', icon: IconChartSankey, path: 'flow', permission: 'cluster:read' }],
  streamTopics: {
    flow: ({ clusterId, invalidate }) => invalidate(['clusters', clusterId, 'flow']),
  },
});
