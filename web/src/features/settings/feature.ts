import { IconSettings } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute, featureView } from '../../kernel/routing/roots.ts';
import { SettingsView } from '../../kernel/shell/SettingsView.tsx';
import { DisplaySection, OperationalSection } from './sections.tsx';

const settingsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'settings',
  component: featureView('settings', SettingsView),
});

/** The Settings page and its first sections: display preferences and operational configuration. */
export const settingsFeature = defineFeature({
  contract: CONTRACT,
  id: 'settings',
  routes: { cluster: [settingsRoute] },
  nav: [
    { group: 'configuration', order: 30, label: 'Settings', icon: IconSettings, path: 'settings', permission: 'settings:read' },
  ],
  slots: {
    'settings.sections': [
      { id: 'settings-display', order: 10, title: 'Display', Component: DisplaySection },
      { id: 'settings-operational', order: 20, title: 'Operational configuration', Component: OperationalSection },
    ],
  },
});
