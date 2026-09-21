import { IconAdjustmentsHorizontal, IconGitCompare } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { configTopic } from './applyProgress.ts';
import { ConfigDiffView } from './ConfigDiffView.tsx';
import { ConfigurationView } from './ConfigurationView.tsx';
import { RegistrationRecommendations } from './RegistrationRecommendations.tsx';

/**
 * The declaration's navigable state: which mode is open, which editor, and — on
 * the routing builder — the address its bounded region is drawn around and the
 * element that is selected (ADR-0067, ADR-0087, ADR-0090 D9). All of it lives in
 * the URL so a view can be shared and restored.
 */
export interface ConfigurationSearch {
  tab?: 'declared' | 'routing' | 'history' | 'recommended';
  section?: 'addresses' | 'addressSettings' | 'securitySettings' | 'diverts' | 'bridges';
  item?: string;
  anchor?: string;
  selected?: string;
}

const SECTIONS = ['addresses', 'addressSettings', 'securitySettings', 'diverts', 'bridges'];

function validateConfigurationSearch(raw: Record<string, unknown>): ConfigurationSearch {
  const out: ConfigurationSearch = {};
  if (typeof raw.tab === 'string' && ['declared', 'routing', 'history', 'recommended'].includes(raw.tab)) {
    out.tab = raw.tab as ConfigurationSearch['tab'];
  }
  if (typeof raw.section === 'string' && SECTIONS.includes(raw.section)) {
    out.section = raw.section as ConfigurationSearch['section'];
  }
  if (typeof raw.item === 'string' && raw.item) out.item = raw.item;
  if (typeof raw.anchor === 'string' && raw.anchor) out.anchor = raw.anchor;
  if (typeof raw.selected === 'string' && raw.selected) out.selected = raw.selected;
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
