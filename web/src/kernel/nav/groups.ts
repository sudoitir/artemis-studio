/**
 * The navigation groups, in the order the rail shows them (ADR-0070). The list is closed:
 * a feature names one of these for each view it contributes, and adding a group is a
 * kernel change, which keeps the rail coherent as features are added.
 */
export const NAV_GROUPS = [
  { id: 'observe', label: 'Observe' },
  { id: 'messaging', label: 'Messaging' },
  { id: 'resources', label: 'Resources' },
  { id: 'configuration', label: 'Configuration' },
  { id: 'activity', label: 'Activity' },
] as const;

export type NavGroupId = (typeof NAV_GROUPS)[number]['id'];
