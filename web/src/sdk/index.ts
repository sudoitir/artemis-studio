/**
 * `@artemis-studio/plugin-sdk` — everything a plugin's UI may use from Studio (ADR-0100). The host
 * shares this module as a singleton, so a plugin's bundle uses the running Studio's own copy:
 * the same router roots, the same query cache, the same permission checks. Nothing outside this
 * file is part of the contract; a plugin that reaches past it breaks on the next Studio release.
 */
import type { ComponentType, ReactElement } from 'react';

import { CONTRACT, type PluginId, type StudioFeature } from '../kernel/feature.ts';
import { featureView as kernelFeatureView } from '../kernel/routing/roots.ts';

export { CONTRACT };
export type {
  NavContribution,
  PaletteSource,
  PluginId,
  RouteContributions,
  StudioFeature,
  TopicHandler,
} from '../kernel/feature.ts';
export {
  SETTINGS_GROUPS,
  type MessageSelection,
  type QueueSelection,
  type SettingsGroupId,
  type SlotContribution,
  type SlotContributions,
  type SlotName,
  type SlotProps,
} from '../kernel/slots.ts';
export { NAV_GROUPS, type NavGroupId } from '../kernel/nav/groups.ts';
export { clusterRoute, rootRoute } from '../kernel/routing/roots.ts';
export { ApiError, clusterKey, request } from '../kernel/api/request.ts';
export { useCan } from '../kernel/auth/useCan.ts';
export { useMe } from '../kernel/auth/api.ts';
export { ConfirmByTyping } from '../ui/ConfirmByTyping.tsx';
export { NodeOutcomeSummary, OutcomeSummary, type OutcomeRow } from '../ui/NodeOutcomeSummary.tsx';
export { Pager } from '../ui/Pager.tsx';
export { VirtualTable, type GridColumn } from '../ui/VirtualTable.tsx';
/**
 * Shows a notification in Studio's own notification area. Import this, never
 * `@mantine/notifications` directly: that is not shared, so a plugin's own copy would show nothing.
 */
export { notifications as notify } from '@mantine/notifications';

/** A plugin's description of itself: a {@link StudioFeature} whose id is the plugin's own. */
export interface StudioPlugin extends StudioFeature {
  id: PluginId;
}

/** Declares a plugin's UI; its bundle exposes the result as `./feature`'s default export. */
export function definePlugin<T extends StudioPlugin>(plugin: T): T {
  return plugin;
}

/**
 * A route path under the plugin's own namespace, the only place its routes may live:
 * `pluginPath('acme-notes', 'notes/$noteId')` is `p/acme-notes/notes/$noteId`. Use it with
 * `rootRoute` for a page outside any cluster, or `clusterRoute` for a view of one cluster.
 */
export function pluginPath(id: PluginId, path = ''): string {
  const rest = path.replace(/^\/+/, '');
  return rest ? `p/${id}/${rest}` : `p/${id}`;
}

/**
 * The API path of a plugin's own endpoints, for {@link request}: `/p/<id>/…`, or
 * `/clusters/<clusterId>/p/<id>/…` for a cluster-scoped call.
 */
export function pluginApi(id: PluginId, path: string, clusterId?: string): string {
  const rest = path.replace(/^\/+/, '');
  return clusterId ? `/clusters/${clusterId}/p/${id}/${rest}` : `/p/${id}/${rest}`;
}

/** The plugin's page, or the page explaining it is unavailable when the plugin is not running. */
export function pluginView(id: PluginId, View: ComponentType): () => ReactElement {
  return kernelFeatureView(id, View);
}
