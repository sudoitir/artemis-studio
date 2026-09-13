import { IconRoute } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { validateResourceSearch } from '../../kernel/routing/search.ts';
import { RoutingView } from './RoutingView.tsx';

/** Routing carries one extra piece of navigable state: which of its two tabs is open. */
const routingRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'routing',
  component: featureView('routing', RoutingView),
  validateSearch: (raw: Record<string, unknown>) => {
    const base = validateResourceSearch(raw);
    return raw.tab === 'bridges' ? { ...base, tab: 'bridges' as const } : base;
  },
});

/** Diverts and bridges across the cluster. */
export const routingFeature = defineFeature({
  contract: CONTRACT,
  id: 'routing',
  routes: { cluster: [routingRoute] },
  nav: [
    { group: 'resources', order: 60, label: 'Routing', icon: IconRoute, path: 'routing', permission: 'cluster:read' },
  ],
});
