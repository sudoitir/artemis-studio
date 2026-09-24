import { IconAdjustmentsHorizontal, IconGitCompare } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { configTopic } from './applyProgress.ts';
import { ConfigDiffView } from './ConfigDiffView.tsx';
import { ConfigurationView } from './ConfigurationView.tsx';
import { RegistrationRecommendations } from './RegistrationRecommendations.tsx';
import { RoutingBuilderTab } from './routing/RoutingBuilderTab.tsx';
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

const configDiffRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'config-diff',
  component: featureView('brokerconfig', ConfigDiffView),
});

const configurationRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'configuration',
  component: featureView('brokerconfig', ConfigurationView),
  validateSearch: validateConfigurationSearch,
});

/** Declared broker configuration: one desired-vs-live screen with its apply, history, and comparing two nodes. */
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
    { group: 'configuration', order: 20, label: 'Config diff', icon: IconGitCompare, path: 'config-diff', permission: 'cluster:read' },
  ],
  slots: {
    'cluster.registration.afterProbe': [
      { id: 'brokerconfig-recommendations', order: 10, Component: RegistrationRecommendations },
    ],
    // The routing builder edits this module's declaration, and is hosted by the Routing screen
    // (ADR-0094). Its id is the tab's `?tab=`.
    'routing.tabs': [{ id: 'builder', order: 10, title: 'Builder', Component: RoutingBuilderTab }],
  },
  streamTopics: { config: configTopic },
});
