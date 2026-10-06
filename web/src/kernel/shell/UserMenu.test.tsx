import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { manifestHandler } from '../../test/manifest.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../api/paging.ts';

const radio = (name: string) => screen.findByRole('menuitemradio', { name });

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
});
