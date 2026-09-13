import { IconAdjustmentsHorizontal, IconGitCompare } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { configTopic } from './applyProgress.ts';
import { ApplyView } from './ApplyView.tsx';
import { ConfigDiffView } from './ConfigDiffView.tsx';
import { ConfigurationView } from './ConfigurationView.tsx';
import { RegistrationRecommendations } from './RegistrationRecommendations.tsx';

/** The declaration's navigable state: which tab is open, and which editor (ADR-0067). */
export interface ConfigurationSearch {
  tab?: 'declared' | 'drift' | 'history' | 'recommended';
  section?: 'addresses' | 'addressSettings' | 'securitySettings' | 'diverts';
  item?: string;
}

function validateConfigurationSearch(raw: Record<string, unknown>): ConfigurationSearch {
  const out: ConfigurationSearch = {};
  if (typeof raw.tab === 'string' && ['declared', 'drift', 'history', 'recommended'].includes(raw.tab)) {
    out.tab = raw.tab as ConfigurationSearch['tab'];
  }
  if (
    typeof raw.section === 'string' &&
    ['addresses', 'addressSettings', 'securitySettings', 'diverts'].includes(raw.section)
  ) {
    out.section = raw.section as ConfigurationSearch['section'];
  }
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

/** The apply flow is its own address: a plan being confirmed is something worth a link. */
const configurationApplyRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'configuration/apply',
  component: featureView('brokerconfig', ApplyView),
});

/** Declared broker configuration: drift, apply with its plan, history, and comparing two nodes. */
export const brokerconfigFeature = defineFeature({
  contract: CONTRACT,
  id: 'brokerconfig',
  routes: { cluster: [configDiffRoute, configurationRoute, configurationApplyRoute] },
  nav: [
    {
      group: 'configuration',
      order: 10,
      label: 'Configuration',
      icon: IconAdjustmentsHorizontal,
      path: 'configuration',
      permission: 'cluster:read',
    },
    { group: 'configuration', order: 20, label: 'Config diff', icon: IconGitCompare, path: 'config-diff', permission: 'cluster:read' },
  ],
  slots: {
    'cluster.registration.afterProbe': [
      { id: 'brokerconfig-recommendations', order: 10, Component: RegistrationRecommendations },
    ],
  },
  streamTopics: { config: configTopic },
});
