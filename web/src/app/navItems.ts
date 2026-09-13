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
  type Icon,
} from '@tabler/icons-react';

export interface NavItemDef {
  path: string;
  label: string;
  icon: Icon;
}

/**
 * The per-cluster view nav (moved out of `ClusterLayout`'s horizontal view strip,
 * ADR-0034). Order matches the old `VIEWS` tuple with `metrics` added.
 */
export const NAV_ITEMS: NavItemDef[] = [
  { path: 'topology', label: 'Topology', icon: IconSitemap },
  { path: 'queues', label: 'Queues', icon: IconListDetails },
  { path: 'sql', label: 'SQL Console', icon: IconTerminal2 },
  { path: 'metrics', label: 'Metrics', icon: IconChartLine },
  { path: 'alerts', label: 'Alerts', icon: IconBell },
  { path: 'addresses', label: 'Addresses', icon: IconAt },
  { path: 'consumers', label: 'Consumers', icon: IconUsers },
  { path: 'sessions', label: 'Sessions', icon: IconPlugConnected },
  { path: 'connections', label: 'Connections', icon: IconNetwork },
  { path: 'producers', label: 'Producers', icon: IconSend },
  { path: 'routing', label: 'Routing', icon: IconRoute },
  { path: 'events', label: 'Events', icon: IconBellRinging },
  { path: 'rr', label: 'Requests', icon: IconArrowsExchange },
  { path: 'dlq', label: 'DLQ', icon: IconAlertTriangle },
  { path: 'configuration', label: 'Configuration', icon: IconAdjustmentsHorizontal },
  { path: 'config-diff', label: 'Config diff', icon: IconGitCompare },
  { path: 'audit', label: 'Audit', icon: IconClipboardList },
  { path: 'settings', label: 'Settings', icon: IconSettings },
];
