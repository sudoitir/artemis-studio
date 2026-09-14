import type { ComponentType } from 'react';
import type { SpotlightActionGroupData } from '@mantine/spotlight';
import type { Icon } from '@tabler/icons-react';
import type { AnyRoute } from '@tanstack/react-router';

import type { NavGroupId } from './nav/groups.ts';
import type { SlotContributions } from './slots.ts';

/** The extension contract version, shared with the backend's `Contract.VERSION` (ADR-0070). */
export const CONTRACT = 1;

/** The module ids the backend manifest reports. A frontend feature uses the same id as its backend module. */
export const FEATURE_IDS = [
  'security',
  'audit',
  'settings',
  'stream',
  'broker',
  'clusters',
  'scrape',
  'mcp',
  'queues',
  'resources',
  'messages',
  'routing',
  'metrics',
  'alerting',
  'events',
  'rr',
  'sql',
  'brokerconfig',
  'triage',
  'apitokens',
  'identity-local',
  'identity-oidc',
] as const;

export type FeatureId = (typeof FEATURE_IDS)[number];

/**
 * The routes a feature adds, each created with a kernel root from `routing/roots.ts` as its parent. The
 * composition root adds every installed feature's routes whether or not the feature is enabled, so a deep
 * link to a disabled feature reaches the page that explains it rather than a not-found.
 */
export interface RouteContributions {
  /** Pages outside any cluster, children of `rootRoute`. */
  root?: AnyRoute[];
  /** Views of one cluster, children of `clusterRoute`. */
  cluster?: AnyRoute[];
}

/** One view in a cluster's navigation and command palette. */
export interface NavContribution {
  group: NavGroupId;
  /** Position within the group; lower comes first. */
  order: number;
  label: string;
  icon: Icon;
  /** The view's path below `/clusters/$clusterId/`. */
  path: string;
  /**
   * The permission the view reads with. Without it the entry stays visible and disabled,
   * and says why; the server enforces it regardless.
   */
  permission?: string;
  /** Shown after the label, such as a count of what needs attention. */
  Badge?: ComponentType<{ clusterId: string }>;
}

/**
 * A feature's command-palette groups. It is rendered inside the palette, so it may use hooks, and calls
 * `report` whenever its groups change; `clusterId` is the cluster in view, if there is one.
 */
export type PaletteSource = ComponentType<{
  clusterId?: string;
  report: (groups: SpotlightActionGroupData[]) => void;
}>;

/** Handles one frame of a stream topic the feature owns (ADR-0070). */
export type TopicHandler = (frame: {
  clusterId: string;
  /** The frame's payload, as the server sent it. */
  data: string;
  /** Invalidates a query key, or only marks it stale while refreshing is paused. */
  invalidate: (queryKey: readonly unknown[]) => void;
}) => void;

/** What a frontend feature contributes to the shell (ADR-0070). */
export interface StudioFeature {
  contract: typeof CONTRACT;
  id: FeatureId;
  routes?: RouteContributions;
  nav?: NavContribution[];
  palette?: PaletteSource;
  slots?: SlotContributions;
  /**
   * A handler per stream topic the feature's backend module declares. A cluster's layout subscribes to
   * the topics of every enabled feature.
   */
  streamTopics?: Record<string, TopicHandler>;
}

export function defineFeature<T extends StudioFeature>(feature: T): T {
  return feature;
}
