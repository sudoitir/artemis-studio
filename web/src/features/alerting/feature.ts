import { IconBell } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { clusterKey } from '../../kernel/api/request.ts';
import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { AlertsView } from './AlertsView.tsx';
import { keys } from './api.ts';
import { FiringBadge } from './FiringBadge.tsx';
import { FiringNodeMark } from './FiringNodeMark.tsx';
import { FiringTotal } from './FiringTotal.tsx';
import { ChannelsSection } from './sections.tsx';

const alertsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'alerts',
  component: featureView('alerting', AlertsView),
  validateSearch: (raw: Record<string, unknown>): { tab?: 'firing' | 'history' | 'rules' } =>
    raw.tab === 'firing' || raw.tab === 'history' || raw.tab === 'rules' ? { tab: raw.tab } : {},
});

/** Alert rules, what is firing and its history, and the channels a firing is sent to. */
export const alertingFeature = defineFeature({
  contract: CONTRACT,
  id: 'alerting',
  routes: { cluster: [alertsRoute] },
  nav: [
    { group: 'observe', order: 30, label: 'Alerts', icon: IconBell, path: 'alerts', hotkey: 'l', permission: 'alert:read', Badge: FiringBadge },
  ],
  slots: {
    'shell.header': [{ id: 'alerting-firing-total', order: 10, Component: FiringTotal }],
    'topology.node.marks': [{ id: 'alerting-firing', order: 10, Component: FiringNodeMark }],
    'settings.sections': [
      { id: 'alerting-channels', order: 70, group: 'studio', title: 'Notification channels', Component: ChannelsSection },
    ],
  },
  streamTopics: {
    alerts: ({ clusterId, invalidate }) => {
      invalidate(clusterKey(clusterId, 'alerts'));
      invalidate(keys.firingCounts);
    },
  },
});
