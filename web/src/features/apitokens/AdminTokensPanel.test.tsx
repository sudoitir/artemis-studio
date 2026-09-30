import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { AdminTokensPanel } from './AdminTokensPanel.tsx';

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
    server.use(http.get('*/api/v1/admin/tokens', () => HttpResponse.json([LEAKED])));
    renderWithProviders(<AdminTokensPanel />);

    expect(await screen.findByText('grace')).toBeInTheDocument();
    expect(screen.getByText('ci-bot')).toBeInTheDocument();
    expect(screen.getByText('stale')).toBeInTheDocument();
  });

  it('revokes another user’s key after its name is typed', async () => {
    let revoked = false;
    me(['token:admin']);
    server.use(
      http.get('*/api/v1/admin/tokens', () => HttpResponse.json([LEAKED])),
      http.delete('*/api/v1/admin/tokens/t7', () => {
        revoked = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AdminTokensPanel />);

    await user.click(await screen.findByRole('button', { name: 'Revoke ci-bot of grace' }));
    await user.type(await screen.findByRole('textbox', { name: /Type "ci-bot" to confirm/ }), 'ci-bot');
    await user.click(screen.getByRole('button', { name: 'Revoke key' }));

    await expect.poll(() => revoked).toBe(true);
  });

  it('explains the missing permission instead of an empty list', async () => {
    me(['cluster:read']);
    renderWithProviders(<AdminTokensPanel />);

    expect(await screen.findByText(/You cannot see other users/)).toBeInTheDocument();
  });
});
