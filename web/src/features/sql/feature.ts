import { IconTerminal2 } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { IndexSection } from './sections.tsx';
import { SqlConsoleView } from './SqlConsoleView.tsx';

/**
 * The SQL Console's navigable state (ADR-0058). The query text is the whole of
 * it: the source is the `FROM` qualifier inside that text, so a separate `source`
 * parameter would give one fact two owners and let them drift. `live` is the
 * other half of what is being viewed: a console linked while tailing opens
 * tailing.
 */
export interface SqlSearch {
  q?: string;
  live?: boolean;
}

function validateSqlSearch(raw: Record<string, unknown>): SqlSearch {
  const out: SqlSearch = {};
  if (typeof raw.q === 'string' && raw.q) out.q = raw.q;
  // Only the true case is carried, so an idle console has a clean address.
  if (raw.live === true || raw.live === 'true') out.live = true;
  return out;
}

const sqlRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'sql',
  component: featureView('sql', SqlConsoleView),
  validateSearch: validateSqlSearch,
});

/** The SQL Console over broker messages, and the message index it can search after consumption. */
export const sqlFeature = defineFeature({
  contract: CONTRACT,
  id: 'sql',
  routes: { cluster: [sqlRoute] },
  nav: [
    { group: 'messaging', order: 30, label: 'SQL Console', icon: IconTerminal2, path: 'sql', hotkey: 's', permission: 'message:read' },
  ],
  slots: {
    'settings.sections': [{ id: 'sql-index', order: 60, group: 'studio', title: 'Message index', Component: IndexSection }],
  },
});
