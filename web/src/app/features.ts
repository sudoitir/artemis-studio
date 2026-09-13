import {
  IconAdjustmentsHorizontal,
  IconAlertTriangle,
  IconArrowsExchange,
  IconAt,
  IconBell,
  IconBellRinging,
  IconChartLine,
  IconClipboardList,
  IconGitCompare,
  IconListDetails,
  IconNetwork,
  IconPlugConnected,
  IconRoute,
  IconSend,
  IconSettings,
  IconSitemap,
  IconTerminal2,
  IconUsers,
} from '@tabler/icons-react';

import { FiringBadge } from '../alerts/FiringBadge.tsx';
import { RegistrationRecommendations } from '../brokerconfig/RegistrationRecommendations.tsx';
import { CONTRACT, defineFeature, type StudioFeature } from '../kernel/feature.ts';
import { QueueHistoryPanels } from '../metrics/QueueHistoryPanels.tsx';
import { LatencyCard } from '../rr/LatencyCard.tsx';

const CLUSTER_READ = 'cluster:read';
const MESSAGE_READ = 'message:read';

/**
 * The composition root (ADR-0069): the one list of frontend features. Each feature uses its
 * backend module's id, so the manifest decides which of them this installation offers.
 */
export const FEATURES: StudioFeature[] = [
  defineFeature({
    contract: CONTRACT,
    id: 'clusters',
    nav: [
      { group: 'observe', order: 10, label: 'Topology', icon: IconSitemap, path: 'topology', permission: CLUSTER_READ },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'metrics',
    nav: [
      { group: 'observe', order: 20, label: 'Metrics', icon: IconChartLine, path: 'metrics', permission: CLUSTER_READ },
    ],
    slots: {
      'queue.detail.panels': [{ id: 'metrics-queue-history', order: 10, Component: QueueHistoryPanels }],
    },
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'alerting',
    nav: [
      {
        group: 'observe',
        order: 30,
        label: 'Alerts',
        icon: IconBell,
        path: 'alerts',
        permission: 'alert:read',
        Badge: FiringBadge,
      },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'rr',
    nav: [
      { group: 'observe', order: 40, label: 'Requests', icon: IconArrowsExchange, path: 'rr', permission: CLUSTER_READ },
    ],
    slots: {
      'metrics.panels': [{ id: 'rr-latency', order: 10, Component: LatencyCard }],
    },
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'queues',
    nav: [
      { group: 'messaging', order: 10, label: 'Queues', icon: IconListDetails, path: 'queues', permission: CLUSTER_READ },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'messages',
    nav: [
      { group: 'messaging', order: 20, label: 'DLQ', icon: IconAlertTriangle, path: 'dlq', permission: MESSAGE_READ },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'sql',
    nav: [
      { group: 'messaging', order: 30, label: 'SQL Console', icon: IconTerminal2, path: 'sql', permission: MESSAGE_READ },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'resources',
    nav: [
      { group: 'resources', order: 10, label: 'Addresses', icon: IconAt, path: 'addresses', permission: CLUSTER_READ },
      { group: 'resources', order: 20, label: 'Consumers', icon: IconUsers, path: 'consumers', permission: CLUSTER_READ },
      { group: 'resources', order: 30, label: 'Sessions', icon: IconPlugConnected, path: 'sessions', permission: CLUSTER_READ },
      { group: 'resources', order: 40, label: 'Connections', icon: IconNetwork, path: 'connections', permission: CLUSTER_READ },
      { group: 'resources', order: 50, label: 'Producers', icon: IconSend, path: 'producers', permission: CLUSTER_READ },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'routing',
    nav: [
      { group: 'resources', order: 60, label: 'Routing', icon: IconRoute, path: 'routing', permission: CLUSTER_READ },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'brokerconfig',
    nav: [
      {
        group: 'configuration',
        order: 10,
        label: 'Configuration',
        icon: IconAdjustmentsHorizontal,
        path: 'configuration',
        permission: CLUSTER_READ,
      },
      { group: 'configuration', order: 20, label: 'Config diff', icon: IconGitCompare, path: 'config-diff', permission: CLUSTER_READ },
    ],
    slots: {
      'cluster.registration.afterProbe': [
        { id: 'brokerconfig-recommendations', order: 10, Component: RegistrationRecommendations },
      ],
    },
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'settings',
    nav: [
      { group: 'configuration', order: 30, label: 'Settings', icon: IconSettings, path: 'settings', permission: 'settings:read' },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'events',
    nav: [
      { group: 'activity', order: 10, label: 'Events', icon: IconBellRinging, path: 'events', permission: CLUSTER_READ },
    ],
  }),
  defineFeature({
    contract: CONTRACT,
    id: 'audit',
    nav: [
      { group: 'activity', order: 20, label: 'Audit', icon: IconClipboardList, path: 'audit', permission: CLUSTER_READ },
    ],
  }),
];
