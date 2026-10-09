import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRoute } from '@tanstack/react-router';

import { paged } from '../api/paging.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { CONTRACT, defineFeature, type StudioFeature } from '../feature.ts';
import { FeatureProvider } from '../FeatureProvider.tsx';
import { rootRoute } from '../routing/roots.ts';
import { Section } from '../../ui/Section.tsx';
import { AdminView } from './AdminView.tsx';

/**
 * The Administration page: one page with a single h1, a tab per contribution under its group's
 * heading, the open tab in the address, and operable from the keyboard. Driven by a feature list of
 * its own so it is tested on what the slot hands it.
 */
const route = createRoute({
  getParentRoute: () => rootRoute,
  path: 'admin-under-test',
  component: () => (
    <FeatureProvider features={features}>
      <AdminView />
    </FeatureProvider>
  ),
  validateSearch: (raw: Record<string, unknown>): { tab?: string } =>
    typeof raw.tab === 'string' && raw.tab ? { tab: raw.tab } : {},
});

const panel = (title: string, text: string) => () => (
  <Section title={title}>
    <p>{text}</p>
  </Section>
);

// Contributed out of group order, and with no Governance tab, so the page's own order and the hidden
// empty group are what the tests see.
const features: StudioFeature[] = [
  defineFeature({
    contract: CONTRACT,
    id: 'security',
    routes: { root: [route] },
    slots: {
      'admin.tabs': [
        {
          id: 'diagnostics',
          order: 5,
          title: 'Diagnostics',
          group: 'support',
          Component: panel('Diagnostics', 'A bundle'),
        },
        { id: 'users', order: 10, title: 'Users', group: 'access', Component: panel('Users', 'Who can sign in') },
        { id: 'plugins', order: 15, title: 'Plugins', group: 'installation', Component: panel('Plugins', 'Installed') },
        { id: 'teams', order: 22, title: 'Teams', group: 'access', Component: panel('Teams', 'Who owns what') },
      ],
    },
  }),
];

describe('the Administration page', () => {
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
      http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
    );
  });

  it('lists its sections under Access, Installation, Governance and Support, leaving out an empty group', async () => {
    renderAppAt('/admin-under-test', features);

    const list = await screen.findByRole('navigation', { name: 'Administration sections' });
    expect(list.textContent).toBe('AccessUsersTeamsInstallationPluginsSupportDiagnostics');
    expect(
      within(list)
        .getAllByRole('group')
        .map((group) => group.getAttribute('aria-labelledby')),
    ).toHaveLength(3);
    // The open section is the one link marked as the current page; the first opens when the address names none.
    expect(
      within(list)
        .getAllByRole('link', { current: 'page' })
        .map((link) => link.textContent),
    ).toEqual(['Users']);
    expect(screen.getByText('Who can sign in')).toBeInTheDocument();
  });

  it('is one page: a single h1, and the open panel brings its h2', async () => {
    renderAppAt('/admin-under-test', features);

    await screen.findByRole('navigation', { name: 'Administration sections' });
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual(['Administration']);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Users']);
  });

  it('opens the section the address names, as a reload or a shared link does, and keeps a chosen one there', async () => {
    const { router } = renderAppAt('/admin-under-test?tab=plugins', features);

    expect(await screen.findByText('Installed')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Plugins' })).toHaveAttribute('aria-current', 'page');

    await userEvent.click(screen.getByRole('link', { name: 'Diagnostics' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'diagnostics' }));
    expect(screen.getByText('A bundle')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Diagnostics' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Plugins' })).not.toHaveAttribute('aria-current');
  });

  it('opens Teams for the link a team administrator follows', async () => {
    renderAppAt('/admin-under-test?tab=teams', features);

    const teams = await screen.findByRole('group', { name: 'Teams' });
    expect(within(teams).getByText('Who owns what')).toBeInTheDocument();
  });

  it('falls back to the first section for an address naming none it has', async () => {
    renderAppAt('/admin-under-test?tab=gone', features);

    expect(await screen.findByText('Who can sign in')).toBeInTheDocument();
  });

  it('does not scroll the page when a section is chosen with the pointer', async () => {
    renderAppAt('/admin-under-test', features);
    const plugins = await screen.findByRole('link', { name: 'Plugins' });
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);

    await userEvent.click(plugins);
    expect(await screen.findByText('Installed')).toBeInTheDocument();
    expect(scrollTo.mock.calls).toEqual([]);
    scrollTo.mockRestore();
  });

  it('is reached with Tab, opened with Enter, and moves focus into the section', async () => {
    renderAppAt('/admin-under-test', features);
    const user = userEvent.setup();

    const users = await screen.findByRole('link', { name: 'Users' });
    users.focus();
    await user.tab();
    await user.tab();
    expect(screen.getByRole('link', { name: 'Plugins' })).toHaveFocus();
    expect(screen.getByText('Who can sign in')).toBeInTheDocument();

    await user.keyboard('{Enter}');
    const plugins = await screen.findByRole('group', { name: 'Plugins' });
    await waitFor(() => expect(plugins).toHaveFocus());
    expect(within(plugins).getByRole('heading', { level: 2, name: 'Plugins' })).toBeInTheDocument();
  });
});
