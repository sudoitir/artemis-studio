import { IconSettings } from '@tabler/icons-react';
import { createRoute } from '@tanstack/react-router';

import { CONTRACT, defineFeature } from '../../kernel/feature.ts';
import { clusterRoute } from '../../kernel/routing/roots.ts';
import { lazyFeatureView } from '../../kernel/routing/lazy.tsx';
import { DisplaySection, HealthSection, SecuritySection } from './sections.tsx';
import type { SettingsSearch } from './SettingsPage.tsx';

const settingsRoute = createRoute({
  getParentRoute: () => clusterRoute,
  path: 'settings',
  component: lazyFeatureView('settings', () => import('./SettingsPage.tsx'), 'SettingsPage'),
  // The open tab is a category's or a `settings.sections` contribution's id; the page falls back to the first
  // tab for any other. `q` is the search and `modified` the "Modified only" filter.
  validateSearch: (raw: Record<string, unknown>): SettingsSearch => ({
    ...(typeof raw.tab === 'string' && raw.tab ? { tab: raw.tab } : {}),
    ...(typeof raw.q === 'string' && raw.q ? { q: raw.q } : {}),
    ...(raw.modified === true || raw.modified === 'true' ? { modified: true } : {}),
  }),
});

/** The Settings page: Studio's settings by category, and its sections for display, encryption keys and health. */
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
    'settings.sections': [
      { id: 'settings-display', order: 10, group: 'personal', title: 'Display', Component: DisplaySection },
      { id: 'settings-security', order: 30, group: 'studio', title: 'Encryption keys', Component: SecuritySection },
      { id: 'settings-health', order: 40, group: 'studio', title: 'Studio health', Component: HealthSection },
    ],
  },
});
