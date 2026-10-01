import { IconSitemap } from '@tabler/icons-react';
import { createRoute, redirect } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView, lazySlot } from '../../kernel/routing/lazy.tsx';
import { keys } from './api.ts';
import { ClusterHeader } from './ClusterHeader.tsx';
import { ClusterPalette } from './ClusterPalette.tsx';
import { RemoveClusterSection } from './RemoveClusterSection.tsx';
import { ClusterRail } from './ClusterRail.tsx';
import { CapabilitiesSection, CredentialsSection, RegisterSection } from './ClusterSettings.tsx';
import { EnvironmentsPanel } from './EnvironmentsPanel.tsx';

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
  component: lazyFeatureView('clusters', () => import('./TopologyView.tsx'), 'TopologyView'),
});

/** Registered clusters, their topology and environments: the platform every other feature works on. */
export const clustersFeature = defineFeature({
  contract: CONTRACT,
  id: 'clusters',
  routes: { cluster: [clusterIndexRoute, topologyRoute] },
  nav: [
    {
      group: 'observe',
      order: 10,
      label: 'Topology',
      icon: IconSitemap,
      path: 'topology',
      hotkey: 't',
      permission: 'cluster:read',
    },
  ],
  palette: ClusterPalette,
  slots: {
    'shell.navbar': [{ id: 'clusters-rail', order: 10, Component: ClusterRail }],
    'home.empty': [
      { id: 'clusters-home', order: 10, Component: lazySlot(() => import('./ClusterHome.tsx'), 'ClusterHome') },
    ],
    'cluster.header': [{ id: 'clusters-header', order: 10, Component: ClusterHeader }],
    'settings.sections': [
      { id: 'clusters-register', order: 30, group: 'studio', title: 'Clusters', Component: RegisterSection },
      {
        id: 'clusters-credentials',
        order: 40,
        group: 'cluster',
        title: 'Broker credentials',
        Component: CredentialsSection,
      },
      {
        id: 'clusters-capabilities',
        order: 50,
        group: 'cluster',
        title: 'Connection capabilities',
        Component: CapabilitiesSection,
      },
      // Last in the cluster's group, whatever other features contribute to it.
      {
        id: 'clusters-remove',
        order: 1000,
        group: 'cluster',
        title: 'Remove cluster',
        Component: RemoveClusterSection,
      },
    ],
    'admin.tabs': [{ id: 'environments', order: 30, title: 'Environments', Component: EnvironmentsPanel }],
  },
  streamTopics: {
    topology: ({ clusterId, invalidate }) => invalidate(keys.topology(clusterId)),
    health: ({ clusterId, invalidate }) => invalidate(keys.health(clusterId)),
  },
});
