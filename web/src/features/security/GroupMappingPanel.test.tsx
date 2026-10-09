import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { GroupMappingPanel } from './GroupMappingPanel.tsx';
import { holdButton } from '../../test/hold.ts';

const MAPPING = { id: 'm1', groupName: 'ops', roleId: 'r-viewer', roleName: 'VIEWER', scopeType: 'GLOBAL' };

function serve(mappings: unknown[] = [MAPPING]) {
  server.use(
    http.get('*/api/v1/auth/providers', () => HttpResponse.json(paged([{ id: 'okta', label: 'Okta' }]))),
    http.get('*/api/v1/roles', () =>
      HttpResponse.json(
        paged([{ id: 'r-viewer', name: 'VIEWER', builtin: true, permissions: ['cluster:read'], requiresMfa: false }]),
      ),
    ),
    http.get('*/api/v1/clusters', () =>
      HttpResponse.json(
        paged([{ id: 'c-prod', name: 'prod', health: 'OK', nodeCount: 1, updatedAt: '2026-10-01T00:00:00Z' }]),
      ),
    ),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/identity/providers/okta/group-mappings', () =>
      HttpResponse.json({ defaultRoleId: null, mappings }),
    ),
  );
}

function renderMappings() {
  return renderWithProviders(
    <>
      <Notifications />
      <GroupMappingPanel />
    </>,
  );
}

afterEach(() => act(() => notifications.clean()));

describe('GroupMappingPanel', () => {
  it('teaches what a mapping is when there are none', async () => {
    serve([]);
    renderMappings();

    expect(await screen.findByText('No group mappings')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New mapping' })).toBeEnabled();
  });

  it('says no provider is configured instead of showing an empty list', async () => {
    server.use(http.get('*/api/v1/auth/providers', () => HttpResponse.json(paged([]))));
    renderMappings();

    expect(await screen.findByText('No external identity provider')).toBeInTheDocument();
  });

  it('deletes a mapping only once its group is typed, and announces it', async () => {
    serve();
    let deleted = false;
    server.use(
      http.delete('*/api/v1/identity/providers/okta/group-mappings/m1', () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderMappings();

    await user.click(await screen.findByRole('button', { name: 'Delete mapping for ops' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete the mapping for ops' });
    const confirm = within(dialog).getByRole('button', { name: 'Delete mapping' });
    await holdButton(confirm);

    await waitFor(() => expect(deleted).toBe(true));
    expect(await screen.findByRole('status')).toHaveTextContent('Deleted the mapping for ops');
  });

  it('asks for the group and the role on submit, focusing the first missing one', async () => {
    serve([]);
    const user = userEvent.setup();
    renderMappings();

    await user.click(await screen.findByRole('button', { name: 'New mapping' }));
    const dialog = await screen.findByRole('dialog', { name: 'New group mapping' });
    await user.click(within(dialog).getByRole('button', { name: 'Add mapping' }));

    expect(await within(dialog).findByText(/Enter the group name/)).toBeInTheDocument();
    expect(within(dialog).getByText('Choose the role the group grants.')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: /Group/ })).toHaveFocus();
  });

  it('shows where each mapping applies, naming the cluster', async () => {
    serve([MAPPING, { ...MAPPING, id: 'm2', groupName: 'dev', scopeType: 'CLUSTER', scopeId: 'c-prod' }]);
    renderMappings();

    const scoped = await screen.findByRole('row', { name: /dev/ });
    expect(scoped).toHaveTextContent('Cluster prod');
    expect(screen.getByRole('row', { name: /ops/ })).toHaveTextContent('Global');
  });

  it('adds a mapping scoped to a cluster', async () => {
    serve([]);
    let body: unknown;
    server.use(
      http.post('*/api/v1/identity/providers/okta/group-mappings', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...MAPPING, id: 'm9' });
      }),
    );
    const user = userEvent.setup();
    renderMappings();

    await user.click(await screen.findByRole('button', { name: 'New mapping' }));
    const dialog = await screen.findByRole('dialog', { name: 'New group mapping' });
    await user.type(within(dialog).getByRole('textbox', { name: /Group/ }), 'dev');
    await user.click(within(dialog).getByRole('combobox', { name: /Role/ }));
    await user.click((await screen.findAllByRole('option', { name: 'VIEWER', hidden: true })).at(-1)!);
    await user.click(within(dialog).getByRole('combobox', { name: 'Scope' }));
    await user.click(await screen.findByRole('option', { name: 'Cluster', hidden: true }));
    await user.click(within(dialog).getByRole('combobox', { name: /Cluster/ }));
    await user.click(await screen.findByRole('option', { name: 'prod', hidden: true }));
    await user.click(within(dialog).getByRole('button', { name: 'Add mapping' }));

    await waitFor(() =>
      expect(body).toEqual({ groupName: 'dev', roleId: 'r-viewer', scopeType: 'CLUSTER', scopeId: 'c-prod' }),
    );
  });
});
