import { IconSettings } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { DisplaySection, EncryptionKeysPanel, StudioHealthPanel } from './sections.tsx';
import { settingsSearch } from './SettingsPage.tsx';

const settingsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'settings',
  component: lazyFeatureView('settings', () => import('./SettingsPage.tsx'), 'SettingsPage'),
  // The open tab is a category's or a `settings.sections` contribution's id; the page falls back to the first
  // tab for any other. `q` is the search and `modified` the "Modified only" filter.
  validateSearch: settingsSearch,
});

/**
 * A cluster's Settings page, behind `settings:read`: Studio's settings by category. What is not a cluster's lives
 * elsewhere: display preferences on the account page, encryption keys and Studio's health under Administration.
 */
export const settingsFeature = defineFeature({
  contract: CONTRACT,
  id: 'settings',
  routes: { cluster: [settingsRoute] },
  nav: [
    {
      group: 'configuration',
      order: 30,
      label: 'Settings',
      icon: IconSettings,
      path: 'settings',
      permission: 'settings:read',
    },
  ],
  slots: {
    // Yours alone and ungated, so it sits with the rest of what is yours.
    'account.sections': [{ id: 'settings-display', order: 40, title: 'Display', Component: DisplaySection }],
    'admin.tabs': [
      {
        id: 'encryption-keys',
        order: 55,
        title: 'Encryption keys',
        group: 'installation',
        Component: EncryptionKeysPanel,
      },
      { id: 'studio-health', order: 80, title: 'Studio health', group: 'support', Component: StudioHealthPanel },
    ],
  },
});
