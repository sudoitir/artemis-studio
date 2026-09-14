import { IconSitemap } from '@tabler/icons-react';
import { createRoute, redirect } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { keys } from './api.ts';
import { ClusterHeader } from './ClusterHeader.tsx';
import { ClusterHome } from './ClusterHome.tsx';
import { ClusterPalette } from './ClusterPalette.tsx';
import { ClusterRail } from './ClusterRail.tsx';
import { CapabilitiesSection, CredentialsSection, RegisterSection } from './ClusterSettings.tsx';
import { EnvironmentsPanel } from './EnvironmentsPanel.tsx';
import { TopologyView } from './TopologyView.tsx';

/** A cluster's own address opens its topology. */
const clusterIndexRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: '/',
  beforeLoad: ({ params }) => {
    throw redirect({ to: `/clusters/${params.clusterId}/topology` });
  },
});

const topologyRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'topology',
  component: featureView('clusters', TopologyView),
});

/** Registered clusters, their topology and environments: the platform every other feature works on. */
export const clustersFeature = defineFeature({
  contract: CONTRACT,
  id: 'clusters',
  routes: { cluster: [clusterIndexRoute, topologyRoute] },
  nav: [
    { group: 'observe', order: 10, label: 'Topology', icon: IconSitemap, path: 'topology', permission: 'cluster:read' },
  ],
  palette: ClusterPalette,
  slots: {
    'shell.navbar': [{ id: 'clusters-rail', order: 10, Component: ClusterRail }],
    'home.empty': [{ id: 'clusters-home', order: 10, Component: ClusterHome }],
    'cluster.header': [{ id: 'clusters-header', order: 10, Component: ClusterHeader }],
    'settings.sections': [
      { id: 'clusters-register', order: 30, title: 'Clusters', Component: RegisterSection },
      { id: 'clusters-credentials', order: 40, title: 'Broker credentials', Component: CredentialsSection },
      { id: 'clusters-capabilities', order: 50, title: 'Connection capabilities', Component: CapabilitiesSection },
    ],
    'admin.tabs': [{ id: 'environments', order: 30, title: 'Environments', Component: EnvironmentsPanel }],
  },
  streamTopics: {
    topology: ({ clusterId, invalidate }) => invalidate(keys.topology(clusterId)),
    health: ({ clusterId, invalidate }) => invalidate(keys.health(clusterId)),
  },
});
