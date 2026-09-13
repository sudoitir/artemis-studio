import { createElement } from 'react';
import { IconAt, IconNetwork, IconPlugConnected, IconSend, IconUsers } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { validateResourceSearch } from '../../kernel/routing/search.ts';
import { keys } from './api.ts';
import { ResourceView } from './ResourceView.tsx';

const KINDS = [
  { kind: 'addresses', label: 'Addresses', icon: IconAt },
  { kind: 'consumers', label: 'Consumers', icon: IconUsers },
  { kind: 'sessions', label: 'Sessions', icon: IconPlugConnected },
  { kind: 'connections', label: 'Connections', icon: IconNetwork },
  { kind: 'producers', label: 'Producers', icon: IconSend },
] as const;

const resourceRoutes = KINDS.map(({ kind }) =>
  createRoute({
    getParentRoute: () => clusterRoute,
    path: kind,
    component: featureView('resources', () => createElement(ResourceView, { kind })),
    validateSearch: validateResourceSearch,
  }),
);

/** Live cross-node views of addresses, consumers, sessions, connections and producers, and closing connections. */
export const resourcesFeature = defineFeature({
  contract: CONTRACT,
  id: 'resources',
  routes: { cluster: resourceRoutes },
  nav: KINDS.map(({ kind, label, icon }, i) => ({
    group: 'resources' as const,
    order: (i + 1) * 10,
    label,
    icon,
    path: kind,
    permission: 'cluster:read',
  })),
  streamTopics: {
    consumers: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'consumers')),
    sessions: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'sessions')),
    connections: ({ clusterId, invalidate }) => invalidate(keys.topic(clusterId, 'connections')),
  },
});
