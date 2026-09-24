import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRoute } from '@tanstack/react-router';

import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { CONTRACT, defineFeature, type StudioFeature } from '../feature.ts';
import { FeatureProvider } from '../FeatureProvider.tsx';
import { rootRoute } from '../routing/roots.ts';
import { SettingsView } from './SettingsView.tsx';

/**
 * The Settings page's tabs (operator-ui spec): grouped under fixed headings, the open tab in the
 * URL, and operable from the keyboard. Driven by a feature list of its own so the page is tested
 * on what the slot hands it, not on which features happen to be installed.
 */
const route = createRoute({
  getParentRoute: () => rootRoute,
  path: 'settings-under-test',
  // The harness provides the installed features to every slot; this page reads only the ones below.
  component: () => (
    <FeatureProvider features={features}>
      <SettingsView />
    </FeatureProvider>
  ),
  validateSearch: (raw: Record<string, unknown>): { tab?: string } =>
    typeof raw.tab === 'string' && raw.tab ? { tab: raw.tab } : {},
});

const section = (text: string) => () => <p>{text}</p>;

const features: StudioFeature[] = [
  defineFeature({
    contract: CONTRACT,
    id: 'settings',
    routes: { root: [route] },
    slots: {
      'settings.sections': [
        { id: 'credentials', order: 40, group: 'cluster', title: 'Broker credentials', Component: section('Rotate them') },
        { id: 'display', order: 10, group: 'personal', title: 'Display', Component: section('Yours alone') },
        { id: 'operational', order: 20, group: 'studio', title: 'Operational configuration', Component: section('Shared') },
        { id: 'from-a-plugin', order: 5, title: 'Notes', Component: section('A plugin section') },
      ],
    },
  }),
];

describe('the Settings page', () => {
  // The shell gates on `/auth/me` and lists clusters, environments and firing alerts beside any page.
  beforeEach(() => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'test-user',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
      http.get('*/api/v1/clusters', () => HttpResponse.json([])),
      http.get('*/api/v1/environments', () => HttpResponse.json([])),
      http.get('*/api/v1/alerts/firing', () => HttpResponse.json([])),
    );
  });

  it('lists its tabs under the fixed headings, in order, with ungrouped sections under Plugins', async () => {
    renderAppAt('/settings-under-test', features);

    const list = await screen.findByRole('tablist', { name: 'Settings sections' });
    expect(list.textContent).toBe(
      'YoursDisplayStudioOperational configurationThis clusterBroker credentialsPluginsNotes',
    );
    expect(within(list).getAllByRole('tab').map((t) => t.textContent)).toEqual([
      'Display',
      'Operational configuration',
      'Broker credentials',
      'Notes',
    ]);
    expect(screen.getByText('Yours alone')).toBeInTheDocument();
  });

  it('opens the tab the address names, and keeps a chosen tab in the address', async () => {
    const { router } = renderAppAt('/settings-under-test?tab=credentials', features);

    expect(await screen.findByText('Rotate them')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Broker credentials' })).toHaveAttribute('aria-selected', 'true');

    await userEvent.click(screen.getByRole('tab', { name: 'Display' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'display' }));
    expect(screen.getByText('Yours alone')).toBeInTheDocument();
  });

  it('falls back to the first tab for an address naming none it has', async () => {
    renderAppAt('/settings-under-test?tab=gone', features);

    expect(await screen.findByText('Yours alone')).toBeInTheDocument();
  });

  it('moves between tabs with the arrows and opens one with Enter, focusing its heading', async () => {
    renderAppAt('/settings-under-test', features);
    const user = userEvent.setup();

    const first = await screen.findByRole('tab', { name: 'Display' });
    first.focus();
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('tab', { name: 'Operational configuration' })).toHaveFocus();
    expect(screen.getByText('Yours alone')).toBeInTheDocument();

    await user.keyboard('{Enter}');
    const heading = await screen.findByRole('heading', { name: 'Operational configuration' });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(screen.getByText('Shared')).toBeInTheDocument();
  });
});
