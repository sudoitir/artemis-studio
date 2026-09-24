import { useMemo } from 'react';
import { useLocation } from '@tanstack/react-router';

import type { NavContribution, StudioFeature } from '../feature.ts';
import { useFeatures } from '../features.ts';
import { NAV_GROUPS } from './groups.ts';

export interface CurrentView {
  clusterId: string;
  /** The navigation entry the address is under, when there is one. */
  item?: NavContribution;
  /** Its group's label. */
  groupLabel?: string;
}

const CLUSTER_PATH = /^\/clusters\/([^/]+)(?:\/(.*))?$/;

/**
 * Which cluster view an address is under (ADR-0109): the navigation entry whose path is the longest
 * prefix of the path after `/clusters/<id>/`, so a queue's message browser is under Queues and a
 * bulk run under Bulk runs. Null outside any cluster.
 */
export function matchView(pathname: string, features: StudioFeature[]): CurrentView | null {
  const match = CLUSTER_PATH.exec(pathname);
  if (!match) return null;
  const clusterId = decodeURIComponent(match[1]);
  const rest = (match[2] ?? '').replace(/\/+$/, '');
  const item = features
    .flatMap((f) => f.nav ?? [])
    .filter((n) => rest === n.path || rest.startsWith(`${n.path}/`))
    .sort((a, b) => b.path.length - a.path.length)[0];
  const groupLabel = item ? NAV_GROUPS.find((g) => g.id === item.group)?.label : undefined;
  return { clusterId, item, groupLabel };
}

export function useCurrentView(): CurrentView | null {
  const { pathname } = useLocation();
  const features = useFeatures();
  return useMemo(() => matchView(pathname, features), [pathname, features]);
}

/**
 * The address of the same view on another cluster (ADR-0109): an operator comparing two clusters
 * stays on Queues. The view's search is not carried — its filter and its open resource name things
 * that may not exist there. Without a current view, the cluster's landing page.
 */
export function sameViewOn(clusterId: string, view: CurrentView | null): string {
  return view?.item ? `/clusters/${clusterId}/${view.item.path}` : `/clusters/${clusterId}`;
}
