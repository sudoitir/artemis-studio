import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { me } from './fixtures.ts';
import { InstallersDialog } from './InstallersDialog.tsx';

const NOW = new Date().toISOString();
const ada = { userId: 'u1', username: 'ada', grantedAt: NOW, grantedBy: 'root' };
const bob = { userId: 'u2', username: 'bob', grantedAt: NOW, grantedBy: 'ada' };

function installers(rows: unknown[]) {
  server.use(
    me(),
    http.get('*/api/v1/admin/plugins/installers', () => HttpResponse.json(paged(rows))),
  );
}

function open() {
  const user = userEvent.setup();
  renderWithProviders(
    <>
      <Notifications />
      <InstallersDialog opened onClose={() => undefined} />
    </>,
  );
  return user;
}

afterEach(() => act(() => notifications.clean()));

describe('InstallersDialog', () => {
  it('lists who can install, and says the last installer cannot be removed', async () => {
    installers([ada]);
    open();

    expect(await screen.findByRole('row', { name: /ada/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove ada' })).toBeDisabled();
    expect(screen.getByText('The only installer cannot be removed; add another first.')).toBeInTheDocument();
  });

  it('teaches what an installer is when there is none', async () => {
    installers([]);
    open();

    expect(await screen.findByText('No installer yet')).toBeInTheDocument();
  });

  it('says why the list could not be read, with a retry', async () => {
    server.use(
      me(),
      http.get('*/api/v1/admin/plugins/installers', () =>
        HttpResponse.json({ title: 'Error', detail: 'The user store is down.' }, { status: 500 }),
      ),
    );
    open();

    expect(await screen.findByText('The user store is down.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('adds an installer and announces it, and asks for a name when there is none', async () => {
    let granted: unknown = null;
    installers([ada]);
    server.use(
      http.post('*/api/v1/admin/plugins/installers', async ({ request }) => {
        granted = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = open();

    await screen.findByRole('row', { name: /ada/ });
    await user.click(screen.getByRole('button', { name: 'Add' }));
    expect(screen.getByText('Enter a username.')).toBeInTheDocument();
    expect(granted).toBeNull();

    await user.type(screen.getByLabelText(/Add an installer/), 'bob');
    await user.click(screen.getByRole('button', { name: 'Add' }));

    await waitFor(() => expect(granted).toEqual({ username: 'bob' }));
    expect(await screen.findByText('Added installer bob')).toBeInTheDocument();
  });

  it('removes an installer and announces it', async () => {
    let revoked: string | null = null;
    installers([ada, bob]);
    server.use(
      http.delete('*/api/v1/admin/plugins/installers/:id', ({ params }) => {
        revoked = params.id as string;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = open();

    await user.click(await screen.findByRole('button', { name: 'Remove bob' }));

    await waitFor(() => expect(revoked).toBe('u2'));
    expect(await screen.findByText('Removed installer bob')).toBeInTheDocument();
  });
});
