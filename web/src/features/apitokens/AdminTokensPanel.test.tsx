import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { AdminTokensPanel } from './AdminTokensPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';
import { holdButton } from '../../test/hold.ts';

const LEAKED = {
  id: 't7',
  name: 'ci-bot',
  owner: 'grace',
  prefix: 'as_leak',
  expiresAt: new Date(Date.now() + 10 * 86_400_000).toISOString(),
  lastUsedAt: null,
  revokedAt: null,
  createdAt: new Date(Date.now() - 60 * 86_400_000).toISOString(),
  previousValidUntil: null,
  grants: [{ action: 'cluster:read', scopeType: 'GLOBAL', scopeId: '00000000-0000-0000-0000-000000000000' }],
  mcpTools: [],
  stale: true,
};

function renderInventory() {
  return renderWithProviders(
    <>
      <Notifications />
      <AdminTokensPanel />
    </>,
  );
}

afterEach(() => act(() => notifications.clean()));

function me(permissions: string[]) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ada',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
  );
}

describe('AdminTokensPanel', () => {
  it('lists every user’s keys and flags stale ones', async () => {
    me(['token:admin']);
    server.use(http.get('*/api/v1/admin/tokens', () => HttpResponse.json(paged([LEAKED]))));
    renderInventory();

    expect(await screen.findByText('grace')).toBeInTheDocument();
    expect(screen.getByText('ci-bot')).toBeInTheDocument();
    expect(screen.getByText('Stale')).toBeInTheDocument();
  });

  it('revokes another user’s key after its name is typed', async () => {
    let revoked = false;
    me(['token:admin']);
    server.use(
      http.get('*/api/v1/admin/tokens', () => HttpResponse.json(paged([LEAKED]))),
      http.delete('*/api/v1/admin/tokens/t7', () => {
        revoked = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderInventory();

    await user.click(await screen.findByRole('button', { name: 'Revoke ci-bot of grace' }));
    await holdButton(screen.getByRole('button', { name: 'Revoke key' }));

    await expect.poll(() => revoked).toBe(true);
    expect(await screen.findByRole('status')).toHaveTextContent('Revoked grace\'s key "ci-bot"');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('says why a revoke failed and what to do', async () => {
    me(['token:admin']);
    server.use(
      http.get('*/api/v1/admin/tokens', () => HttpResponse.json(paged([LEAKED]))),
      http.delete('*/api/v1/admin/tokens/t7', () =>
        HttpResponse.json({ title: 'Boom', detail: 'Database unavailable' }, { status: 500 }),
      ),
    );
    const user = userEvent.setup();
    renderInventory();

    await user.click(await screen.findByRole('button', { name: 'Revoke ci-bot of grace' }));
    await holdButton(screen.getByRole('button', { name: 'Revoke key' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not revoke grace\'s key "ci-bot"');
    expect(alert).toHaveTextContent('Database unavailable The key still works. Try again.');
  });

  it('teaches that nobody has a key yet', async () => {
    me(['token:admin']);
    server.use(http.get('*/api/v1/admin/tokens', () => HttpResponse.json(paged([]))));
    renderInventory();

    expect(await screen.findByText('No user has a key yet')).toBeInTheDocument();
  });

  it('explains the missing permission instead of an empty list', async () => {
    me(['cluster:read']);
    renderInventory();

    expect(await screen.findByText(/You cannot see other users/)).toBeInTheDocument();
  });
});
