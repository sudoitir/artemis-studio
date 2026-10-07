import { IconAdjustmentsHorizontal, IconGitCompare } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView, lazySlot } from '../../kernel/routing/lazy.tsx';
import { configTopic } from './applyProgress.ts';
import { RegistrationRecommendations } from './RegistrationRecommendations.tsx';
import { DIFF_FILTERS, type DiffFilter } from './configDiffRows.ts';
import { asSection, type Section } from './words.ts';

/**
 * The declaration's navigable state: which mode is open and which editor (ADR-0067, ADR-0087).
 * It lives in the URL so a view can be shared and restored. The routing builder keeps its own on
 * the Routing screen (ADR-0094).
 */
export interface ConfigurationSearch {
  tab?: 'declared' | 'history' | 'recommended';
  section?: Section;
  item?: string;
}

function validateConfigurationSearch(raw: Record<string, unknown>): ConfigurationSearch {
  const out: ConfigurationSearch = {};
  if (typeof raw.tab === 'string' && ['declared', 'history', 'recommended'].includes(raw.tab)) {
    out.tab = raw.tab as ConfigurationSearch['tab'];
  }
  const section = asSection(raw.section);
  if (section) out.section = section;
  if (typeof raw.item === 'string' && raw.item) out.item = raw.item;
  return out;
}

/**
 * The node comparison's navigable state: which keys it lists, the text it filters by, and the nodes
 * it keeps. The drift view is the default and so is absent from the address.
 */
export interface ConfigDiffSearch {
  view?: Exclude<DiffFilter, 'drift'>;
  q?: string;
  nodes?: string[];
}

export function validateConfigDiffSearch(raw: Record<string, unknown>): ConfigDiffSearch {
  const out: ConfigDiffSearch = {};
  if (typeof raw.view === 'string' && DIFF_FILTERS.includes(raw.view as DiffFilter) && raw.view !== 'drift') {
    out.view = raw.view as ConfigDiffSearch['view'];
  }
  if (typeof raw.q === 'string' && raw.q) out.q = raw.q;
  const asked = typeof raw.nodes === 'string' ? [raw.nodes] : raw.nodes;
  if (Array.isArray(asked)) {
    const nodes = asked.filter((n): n is string => typeof n === 'string' && n !== '');
    if (nodes.length > 0) out.nodes = nodes;
  }
  return out;
}

const configDiffRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'config-diff',
  component: lazyFeatureView('brokerconfig', () => import('./ConfigDiffView.tsx'), 'ConfigDiffView'),
  validateSearch: validateConfigDiffSearch,
});

const configurationRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'configuration',
  component: lazyFeatureView('brokerconfig', () => import('./ConfigurationView.tsx'), 'ConfigurationView'),
  validateSearch: validateConfigurationSearch,
});

/** Declared broker configuration: one desired-vs-live screen with its apply and history, and the comparison of every node against the others. */
export const brokerconfigFeature = defineFeature({
  contract: CONTRACT,
  id: 'brokerconfig',
  routes: { cluster: [configDiffRoute, configurationRoute] },
  nav: [
    {
      group: 'configuration',
      order: 10,
      label: 'Configuration',
      icon: IconAdjustmentsHorizontal,
      path: 'configuration',
      hotkey: 'k',
      permission: 'cluster:read',
    },
    {
      group: 'configuration',
      order: 20,
      label: 'Config diff',
      icon: IconGitCompare,
      path: 'config-diff',
      permission: 'cluster:read',
    },
  ],
  slots: {
    'cluster.registration.afterProbe': [
      { id: 'brokerconfig-recommendations', order: 10, Component: RegistrationRecommendations },
    ],
    // The routing builder edits this module's declaration, and is hosted by the Routing screen
    // (ADR-0094). Its id is the tab's `?tab=`.
    'routing.tabs': [
      {
        id: 'builder',
        order: 10,
        title: 'Builder',
        Component: lazySlot(() => import('./routing/RoutingBuilderTab.tsx'), 'RoutingBuilderTab'),
      },
    ],
  },
  streamTopics: { config: configTopic },
});
