import {
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
  IconSend,
  IconSettings,
  IconSitemap,
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
  { path: 'metrics', label: 'Metrics', icon: IconChartLine },
  { path: 'alerts', label: 'Alerts', icon: IconBell },
  { path: 'addresses', label: 'Addresses', icon: IconAt },
  { path: 'consumers', label: 'Consumers', icon: IconUsers },
  { path: 'sessions', label: 'Sessions', icon: IconPlugConnected },
  { path: 'connections', label: 'Connections', icon: IconNetwork },
  { path: 'producers', label: 'Producers', icon: IconSend },
  { path: 'events', label: 'Events', icon: IconBellRinging },
  { path: 'rr', label: 'Requests', icon: IconArrowsExchange },
  { path: 'dlq', label: 'DLQ', icon: IconAlertTriangle },
  { path: 'config-diff', label: 'Config diff', icon: IconGitCompare },
  { path: 'audit', label: 'Audit', icon: IconClipboardList },
  { path: 'settings', label: 'Settings', icon: IconSettings },
];
