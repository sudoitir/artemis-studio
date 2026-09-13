import type { NavContribution, StudioFeature } from './feature.ts';
import { NAV_GROUPS, type NavGroupId } from './nav/groups.ts';

export interface NavGroup {
  id: NavGroupId;
  label: string;
  items: NavContribution[];
}

/**
 * The features' views under the kernel's groups (ADR-0070): groups in their fixed order, views in
 * their declared order, and a group none of the features contributes to left out.
 */
export function navGroups(features: StudioFeature[]): NavGroup[] {
  const items = features.flatMap((feature) => feature.nav ?? []);
  return NAV_GROUPS.map((group) => ({
    id: group.id,
    label: group.label,
    items: items.filter((item) => item.group === group.id).sort((a, b) => a.order - b.order),
  })).filter((group) => group.items.length > 0);
}
