import { beforeEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRoute } from '@tanstack/react-router';

import { paged } from '../api/paging.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { CONTRACT, defineFeature, type StudioFeature } from '../feature.ts';
import { FeatureProvider } from '../FeatureProvider.tsx';
import { rootRoute } from '../routing/roots.ts';
import { AdminView } from './AdminView.tsx';

/**
 * The Administration page: one page with a single h1, a tab per contribution, and the open tab in the
 * address. Driven by a feature list of its own so it is tested on what the slot hands it.
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

const panel = (text: string) => () => <p>{text}</p>;

const features: StudioFeature[] = [
  defineFeature({
    contract: CONTRACT,
    id: 'security',
    routes: { root: [route] },
    slots: {
      'admin.tabs': [
        { id: 'users', order: 10, title: 'Users', Component: panel('Who can sign in') },
        { id: 'roles', order: 20, title: 'Roles', Component: panel('What they may do') },
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

  it('is one page with a single h1, and a tab per contribution, the first open', async () => {
    renderAppAt('/admin-under-test', features);

    expect(await screen.findByRole('heading', { level: 1, name: 'Administration' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Users', 'Roles']);
    expect(screen.getByRole('tab', { name: 'Users' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByText('Who can sign in')).toBeInTheDocument();
  });

  it('keeps the chosen tab in the address, and opens the one the address names', async () => {
    const { router } = renderAppAt('/admin-under-test?tab=roles', features);

    expect(await screen.findByText('What they may do')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'Users' }));
    await waitFor(() => expect(router.state.location.search).toEqual({ tab: 'users' }));
    expect(screen.getByText('Who can sign in')).toBeInTheDocument();
  });
});
