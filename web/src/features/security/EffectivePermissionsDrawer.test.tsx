import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { EffectivePermissionView } from './api.ts';
import { EffectivePermissionsDrawer } from './EffectivePermissionsDrawer.tsx';
import { paged } from '../../kernel/api/paging.ts';

const USER = { id: 'u-1', username: 'alice' };
const CLUSTER_ID = 'c1c1c1c1-0000-0000-0000-000000000000';
const ENV_ID = 'e2e2e2e2-0000-0000-0000-000000000000';

const perm = (over: Partial<EffectivePermissionView>): EffectivePermissionView => ({
  action: 'queue:read',
  description: null,
  scopeType: 'GLOBAL',
  scopeId: null,
  roleId: 'r-1',
  roleName: 'operator',
  via: over.action ?? 'queue:read',
  effective: true,
  reason: null,
  ...over,
});

const PERMISSIONS: EffectivePermissionView[] = [
  perm({ action: 'queue:read', description: 'See queues' }),
  perm({ action: 'queue:write', via: 'queue:*' }),
  perm({
    action: 'cluster:manage',
    scopeType: 'CLUSTER',
    scopeId: CLUSTER_ID,
    roleName: 'cluster-admin',
    effective: false,
    reason: 'This action is global only.',
  }),
  perm({ action: 'audit:read', scopeType: 'ENVIRONMENT', scopeId: ENV_ID, roleName: 'auditor' }),
  perm({ action: 'audit:export', scopeType: 'ENVIRONMENT', scopeId: null, roleName: 'auditor' }),
];

function serve(body: EffectivePermissionView[] | Response) {
  server.use(
    http.get('*/api/v1/users/u-1/effective-permissions', () =>
      body instanceof Response ? body : HttpResponse.json(paged(body)),
    ),
  );
}

const renderDrawer = (user: typeof USER | null = USER, onClose = vi.fn()) =>
  renderWithProviders(<EffectivePermissionsDrawer user={user} onClose={onClose} />);

describe('EffectivePermissionsDrawer', () => {
  it('is closed without a user', () => {
    renderDrawer(null);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('says what a user with no role can do', async () => {
    serve([]);
    renderDrawer();

    expect(await screen.findByRole('dialog', { name: 'Effective permissions of alice' })).toBeInTheDocument();
    expect(await screen.findByText(/holds no role, so they can do nothing/)).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Filter by permission or role' })).toBeNull();
  });

  it('states the failure and retries it', async () => {
    serve(HttpResponse.json({ title: 'Down', detail: 'permissions unavailable' }, { status: 503 }));
    const user = userEvent.setup();
    renderDrawer();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the effective permissions');
    expect(alert).toHaveTextContent('permissions unavailable');

    serve(PERMISSIONS);
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('queue:read')).toBeInTheDocument();
  });

  it('groups permissions per scope and names the role and wildcard each came through', async () => {
    serve(PERMISSIONS);
    renderDrawer();

    await screen.findByText('queue:read');
    expect(screen.getByText('Global')).toBeInTheDocument();
    expect(screen.getByText(`Cluster ${CLUSTER_ID.slice(0, 8)}`)).toBeInTheDocument();
    expect(screen.getByText(`Environment ${ENV_ID.slice(0, 8)}`)).toBeInTheDocument();
    // A scope with no id still names its kind.
    expect(screen.getByText('Environment')).toBeInTheDocument();

    const read = screen.getByRole('row', { name: /queue:read/ });
    expect(read).toHaveTextContent('See queues');
    expect(read).toHaveTextContent('operator');
    expect(read).toHaveTextContent('Granted');
    expect(read).not.toHaveTextContent('through');

    // Granted by a wildcard: the pattern is named.
    expect(screen.getByRole('row', { name: /queue:write/ })).toHaveTextContent('through queue:*');
  });

  it('states a permission that has no effect at its scope, with the reason', async () => {
    serve(PERMISSIONS);
    renderDrawer();

    const row = await screen.findByRole('row', { name: /cluster:manage/ });
    expect(row).toHaveTextContent('No effect at this scope');
    expect(row).toHaveTextContent('This action is global only.');
    expect(row).not.toHaveTextContent('Granted');
  });

  it('filters by permission or role, and offers to clear a filter that matches nothing', async () => {
    serve(PERMISSIONS);
    const user = userEvent.setup();
    renderDrawer();

    const filter = await screen.findByRole('textbox', { name: 'Filter by permission or role' });
    await user.type(filter, 'AUDITOR');
    expect(screen.getByText('audit:read')).toBeInTheDocument();
    expect(screen.queryByText('queue:read')).toBeNull();
    // A scope whose rows are all filtered out disappears with them.
    expect(screen.queryByText('Global')).toBeNull();

    await user.clear(filter);
    await user.type(filter, 'queue:w');
    expect(screen.getByText('queue:write')).toBeInTheDocument();
    expect(screen.queryByText('audit:read')).toBeNull();

    await user.clear(filter);
    await user.type(filter, 'nothing-like-this');
    expect(screen.getByText('Nothing matches “nothing-like-this”.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filter' }));
    expect(filter).toHaveValue('');
    expect(screen.getByText('queue:read')).toBeInTheDocument();
  });

  it('closes on escape', async () => {
    serve([]);
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderDrawer(USER, onClose);

    await screen.findByText(/holds no role/);
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalled();
  });
});
