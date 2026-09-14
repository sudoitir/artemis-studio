import { IconBellRinging } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { EventsView } from './EventsView.tsx';

const eventsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'events',
  component: featureView('events', EventsView),
});

/**
 * The cluster's broker notifications, recorded and live. The live feed mounts its own stream: its
 * frames carry the events themselves, with no resource behind them for a topic handler to refetch.
 */
export const eventsFeature = defineFeature({
  contract: CONTRACT,
  id: 'events',
  routes: { cluster: [eventsRoute] },
  nav: [
    { group: 'activity', order: 10, label: 'Events', icon: IconBellRinging, path: 'events', permission: 'cluster:read' },
  ],
});
