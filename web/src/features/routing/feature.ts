import { IconRoute } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { validateResourceSearch, type ResourceSearch } from '../../kernel/routing/search.ts';
import { RoutingView } from './RoutingView.tsx';
import { CopyDivertName, DeleteDivert } from './rowActions.tsx';

/**
 * Routing's navigable state: the listing's filter, sort and page; which tab is open — Diverts,
 * Bridges or a `routing.tabs` contribution's id; and the string keys a contributed tab keeps in the
 * URL (the builder's open editor, its region anchor and its selection). A contribution narrows
 * those to what it accepts; this route only carries them, because it cannot know them.
 */
export interface RoutingSearch extends ResourceSearch {
  tab?: string;
  section?: string;
  item?: string;
  anchor?: string;
  selected?: string;
}

const PASSED_THROUGH = ['tab', 'section', 'item', 'anchor', 'selected'] as const;

export function validateRoutingSearch(raw: Record<string, unknown>): RoutingSearch {
  const out: RoutingSearch = validateResourceSearch(raw);
  for (const key of PASSED_THROUGH) {
    const value = raw[key];
    if (typeof value === 'string' && value) out[key] = value;
  }
  return out;
}

const routingRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'routing',
  component: featureView('routing', RoutingView),
  validateSearch: validateRoutingSearch,
});

/** Diverts and bridges across the cluster, and whatever other features contribute as a tab. */
export const routingFeature = defineFeature({
  contract: CONTRACT,
  id: 'routing',
  routes: { cluster: [routingRoute] },
  nav: [
    { group: 'resources', order: 60, label: 'Routing', icon: IconRoute, path: 'routing', hotkey: 'o', permission: 'cluster:read' },
  ],
  slots: {
    'divert.actions': [
      { id: 'routing.divert.copy', order: 10, section: 'copy', Component: CopyDivertName },
      { id: 'routing.divert.delete', order: 10, section: 'destroy', Component: DeleteDivert },
    ],
  },
});
