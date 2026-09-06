import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { ApiKeysPanel } from './ApiKeysPanel.tsx';

const CATALOGUE = [
  { action: 'cluster:read', label: 'Read clusters' },
  { action: 'queue:purge', label: 'Purge queues' },
];

function mockBaseApis(grants: unknown[], tokens: unknown[] = []) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({ id: 'u1', username: 'ada', mustChangePassword: false, grants }),
    ),
    http.get('*/api/v1/tokens', () => HttpResponse.json(tokens)),
    http.get('*/api/v1/permissions', () => HttpResponse.json(CATALOGUE)),
    http.get('*/api/v1/clusters', () => HttpResponse.json([])),
  );
}

describe('ApiKeysPanel', () => {
  it('lists existing keys', async () => {
    mockBaseApis(
      [],
      [{ id: 't1', name: 'laptop', prefix: 'as_abcd', lastUsedAt: null, expiresAt: null, revokedAt: null }],
    );
    renderWithProviders(<ApiKeysPanel />);

    expect(await screen.findByText('laptop')).toBeInTheDocument();
    expect(screen.getByText('as_abcd')).toBeInTheDocument();
  });

  it('offers only the permissions the user holds', async () => {
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    const user = userEvent.setup();
    renderWithProviders(<ApiKeysPanel />);

    await user.click(await screen.findByRole('button', { name: 'New key' }));

    expect(await screen.findByLabelText('Read clusters')).toBeInTheDocument();
    // Offering a permission the server would strip out would mint a key that
    // silently does less than the operator ticked.
    expect(screen.queryByLabelText('Purge queues')).not.toBeInTheDocument();
  });

  it('posts the chosen grants and shows the minted value once', async () => {
    let posted: { grants?: { action: string; scopeType: string }[] } = {};
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    server.use(
      http.post('*/api/v1/tokens', async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json(
          {
            token: {
              id: 't9',
              name: 'agent',
              prefix: 'as_zzzz',
              lastUsedAt: null,
              expiresAt: null,
              revokedAt: null,
            },
            value: 'as_zzzz.secret-value',
          },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApiKeysPanel />);

    await user.click(await screen.findByRole('button', { name: 'New key' }));
    await user.type(await screen.findByLabelText(/Name/), 'agent');
    await user.click(await screen.findByLabelText('Read clusters'));
    await user.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByDisplayValue('as_zzzz.secret-value')).toBeInTheDocument();
    expect(posted.grants).toEqual([{ action: 'cluster:read', scopeType: 'GLOBAL', scopeId: null }]);
  });
});
