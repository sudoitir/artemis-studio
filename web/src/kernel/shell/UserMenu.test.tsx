import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { manifestHandler } from '../../test/manifest.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../api/paging.ts';

// jsdom has no layout, so Mantine's popover treats its anchor as detached and hides the dropdown (display:none)
// the moment it has positioned it. The menu is queried with `hidden` for that reason, not because it is closed.
const radio = (name: string) => screen.findByRole('menuitemradio', { name, hidden: true });

describe('UserMenu', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.removeAttribute('data-density');
    server.use(
      manifestHandler(),
      http.get('*/api/v1/alerts/firing', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'viewer',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }],
        }),
      ),
    );
  });

  it('offers the two table densities, marks the one in use and applies a choice to every table', async () => {
    const user = userEvent.setup();
    renderAppAt('/');

    await user.click(await screen.findByRole('button', { name: 'User menu' }));
    expect(await radio('Compact')).toBeChecked();

    await user.click(await radio('Comfortable'));

    expect(document.documentElement.dataset.density).toBe('comfortable');
    expect(await radio('Comfortable')).toBeChecked();
    expect(JSON.parse(window.localStorage.getItem('as:density') ?? 'null')).toBe('comfortable');
  });

  const item = (name: string) => screen.queryByRole('menuitem', { name, hidden: true });

  it('offers Administration to a user administrator, opening its first tab', async () => {
    server.use(
      http.get('*/api/v1/me/access', () =>
        HttpResponse.json({ permissions: ['user:admin'], anywhere: [], canSeeCluster: null, teams: [] }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/');

    await user.click(await screen.findByRole('button', { name: 'User menu' }));

    await waitFor(() => expect(item('Administration')).toHaveAttribute('href', '/admin'));
  });

  it('offers Administration to the admin of a team, who holds nothing globally, opening the Teams tab', async () => {
    server.use(
      http.get('*/api/v1/me/access', () =>
        HttpResponse.json({
          permissions: [],
          anywhere: [],
          canSeeCluster: null,
          teams: [{ teamId: 't1', teamName: 'Orders', roleId: 'r1', roleName: 'TEAM_ADMIN', teamAdmin: true }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderAppAt('/');

    await user.click(await screen.findByRole('button', { name: 'User menu' }));

    await waitFor(() => expect(item('Administration')).toHaveAttribute('href', '/admin?tab=teams'));
  });

  it('does not offer Administration to anyone else', async () => {
    const user = userEvent.setup();
    renderAppAt('/');

    await user.click(await screen.findByRole('button', { name: 'User menu' }));
    await screen.findByRole('menuitem', { name: 'Account', hidden: true });

    expect(item('Administration')).toBeNull();
  });
});
