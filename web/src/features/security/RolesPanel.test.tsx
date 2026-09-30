import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { notifications } from '@mantine/notifications';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { PermissionView, RoleView } from './api.ts';
import { RolesPanel } from './RolesPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';

const CATALOGUE: PermissionView[] = [
  { action: 'queue:create', label: 'Create queues', featureId: 'queues', featureTitle: 'Queues', globalOnly: false },
  { action: 'queue:delete', label: 'Destroy queues', featureId: 'queues', featureTitle: 'Queues', globalOnly: false },
];

const ROLES: RoleView[] = [
  { id: 'r-admin', name: 'ADMIN', builtin: true, permissions: ['*'], requiresMfa: true },
  {
    id: 'r-1',
    name: 'queue-operator',
    builtin: false,
    permissions: ['queue:create', 'queue:delete'],
    requiresMfa: false,
  },
  { id: 'r-2', name: 'queue-creator', builtin: false, permissions: ['queue:create'], requiresMfa: false },
  { id: 'r-3', name: 'queue-clone', builtin: false, permissions: ['queue:create', 'queue:delete'], requiresMfa: false },
];

function serve(roles: RoleView[] = ROLES, catalogue: PermissionView[] | Response = CATALOGUE) {
  server.use(
    http.get('*/api/v1/roles', () => HttpResponse.json(paged(roles))),
    http.get('*/api/v1/permissions', () =>
      catalogue instanceof Response ? catalogue : HttpResponse.json(paged(catalogue)),
    ),
  );
}

afterEach(() => vi.restoreAllMocks());

describe('RolesPanel list', () => {
  it('counts the roles, marks built-in ones and offers delete only for custom ones', async () => {
    serve();
    renderWithProviders(<RolesPanel />);

    expect(await screen.findByText('4 roles')).toBeInTheDocument();
    const builtin = screen.getByRole('row', { name: /ADMIN/ });
    expect(within(builtin).getByText('built-in')).toBeInTheDocument();
    // A built-in role can still change whether it requires two-step verification, but is never deleted.
    expect(within(builtin).getByRole('button', { name: 'Edit ADMIN' })).toBeInTheDocument();
    expect(within(builtin).queryByRole('button', { name: 'Delete ADMIN' })).toBeNull();
    const custom = screen.getByRole('row', { name: /queue-operator/ });
    expect(custom).toHaveTextContent('queue:create, queue:delete');
    expect(within(custom).getByRole('button', { name: 'Edit queue-operator' })).toBeInTheDocument();
    expect(within(custom).getByRole('button', { name: 'Delete queue-operator' })).toBeInTheDocument();
  });

  it('counts a single role in the singular', async () => {
    serve([ROLES[0]]);
    renderWithProviders(<RolesPanel />);
    expect(await screen.findByText('1 role')).toBeInTheDocument();
  });

  it('deletes a custom role', async () => {
    serve();
    let deleted = '';
    server.use(
      http.delete('*/api/v1/roles/:id', ({ params }) => {
        deleted = String(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'Delete queue-creator' }));
    await waitFor(() => expect(deleted).toBe('r-2'));
  });

  it('says why a role could not be deleted', async () => {
    serve();
    server.use(
      http.delete('*/api/v1/roles/r-2', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'The role is still granted to 2 users.' }, { status: 409 }),
      ),
    );
    const show = vi.spyOn(notifications, 'show').mockReturnValue('n');
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'Delete queue-creator' }));
    await waitFor(() =>
      expect(show).toHaveBeenCalledWith({ message: 'The role is still granted to 2 users.', color: 'red' }),
    );
  });
});

describe('RolesPanel editor', () => {
  it('creates a role from a name and the permissions picked', async () => {
    serve();
    let body: unknown;
    server.use(
      http.post('*/api/v1/roles', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          id: 'r-9',
          name: 'new-role',
          builtin: false,
          permissions: ['queue:create', 'queue:delete'],
        });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    const dialog = await screen.findByRole('dialog', { name: 'New role' });
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'new-role');
    await user.click(await within(dialog).findByRole('checkbox', { name: 'Select all in Queues' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(body).toEqual({ name: 'new-role', permissions: ['queue:create', 'queue:delete'], requiresMfa: false }),
    );
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New role' })).toBeNull());
  });

  it('edits a role with its name and permissions filled in, and saves it under its id', async () => {
    serve();
    let put: { url: string; body: unknown } | undefined;
    server.use(
      http.put('*/api/v1/roles/:id', async ({ request }) => {
        put = { url: new URL(request.url).pathname, body: await request.json() };
        return HttpResponse.json(ROLES[2]);
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'Edit queue-creator' }));
    const dialog = await screen.findByRole('dialog', { name: 'Edit "queue-creator"' });
    const name = within(dialog).getByRole('textbox', { name: /Name/ });
    expect(name).toHaveValue('queue-creator');
    expect(await within(dialog).findByRole('button', { name: /Queues, 1 of 2 selected/ })).toBeInTheDocument();
    await user.clear(name);
    await user.type(name, 'queue-maker');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(put).toEqual({
        url: '/api/v1/roles/r-2',
        body: { name: 'queue-maker', permissions: ['queue:create'], requiresMfa: false },
      }),
    );
  });

  it('keeps the editor open and says why when a save is refused', async () => {
    serve();
    server.use(
      http.post('*/api/v1/roles', () =>
        HttpResponse.json({ title: 'Conflict', detail: 'A role named "x" already exists.' }, { status: 409 }),
      ),
      http.put('*/api/v1/roles/r-2', () =>
        HttpResponse.json({ title: 'Invalid', detail: 'Unknown permission.' }, { status: 400 }),
      ),
    );
    const show = vi.spyOn(notifications, 'show').mockReturnValue('n');
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    let dialog = await screen.findByRole('dialog', { name: 'New role' });
    await user.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'x');
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() =>
      expect(show).toHaveBeenCalledWith({ message: 'A role named "x" already exists.', color: 'red' }),
    );
    expect(screen.getByRole('dialog', { name: 'New role' })).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New role' })).toBeNull());
    await user.click(screen.getByRole('button', { name: 'Edit queue-creator' }));
    dialog = await screen.findByRole('dialog', { name: 'Edit "queue-creator"' });
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(show).toHaveBeenCalledWith({ message: 'Unknown permission.', color: 'red' }));
  });

  it('states that the permission catalogue could not be loaded, and retries it', async () => {
    serve(ROLES, HttpResponse.json({ title: 'Down', detail: 'catalogue unavailable' }, { status: 503 }));
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the permission catalogue');
    expect(alert).toHaveTextContent('catalogue unavailable');

    serve(ROLES, CATALOGUE);
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('checkbox', { name: 'Select all in Queues' })).toBeInTheDocument();
  });

  it('says the permissions are loading while the catalogue has not arrived', async () => {
    server.use(
      http.get('*/api/v1/roles', () => HttpResponse.json(paged(ROLES))),
      http.get('*/api/v1/permissions', () => new Promise(() => {})),
    );
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);

    await user.click(await screen.findByRole('button', { name: 'New role' }));
    expect(await screen.findByText('Loading permissions…')).toBeInTheDocument();
  });
});

describe('RolesPanel compare', () => {
  async function openCompare() {
    const user = userEvent.setup();
    renderWithProviders(<RolesPanel />);
    await user.click(await screen.findByRole('button', { name: 'Compare roles' }));
    const dialog = await screen.findByRole('dialog', { name: 'Compare roles' });
    const pick = async (label: string, role: string) => {
      await user.click(within(dialog).getByRole('combobox', { name: label }));
      const list = await screen.findByRole('listbox', { name: label, hidden: true });
      await user.click(within(list).getByRole('option', { name: role, hidden: true }));
    };
    return { dialog, pick };
  }

  it('asks for two roles before it compares', async () => {
    serve();
    const { dialog, pick } = await openCompare();

    expect(
      within(dialog).getByText('Pick two roles to see the permissions only one of them holds.'),
    ).toBeInTheDocument();
    await pick('First role', 'queue-operator');
    expect(
      within(dialog).getByText('Pick two roles to see the permissions only one of them holds.'),
    ).toBeInTheDocument();
  });

  it('lists what only one of two roles holds', async () => {
    serve();
    const { dialog, pick } = await openCompare();

    await pick('First role', 'queue-operator');
    await pick('Second role', 'queue-creator');

    expect(within(dialog).getByText('Only in queue-operator (1)')).toBeInTheDocument();
    expect(within(dialog).getByRole('list', { name: 'Only in queue-operator' })).toHaveTextContent('queue:delete');
    expect(within(dialog).getByText('Only in queue-creator (0)')).toBeInTheDocument();
    expect(within(dialog).getByText('Nothing')).toBeInTheDocument();
  });

  it('says two roles that hold the same permissions do', async () => {
    serve();
    const { dialog, pick } = await openCompare();

    await pick('First role', 'queue-operator');
    await pick('Second role', 'queue-clone');

    expect(within(dialog).getByRole('status')).toHaveTextContent(
      'queue-operator and queue-clone hold the same permissions.',
    );
  });
});
