import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { ApiKeysPanel } from './ApiKeysPanel.tsx';

const CATALOGUE = [
  {
    action: 'cluster:read',
    label: 'Read clusters',
    featureId: 'clusters',
    featureTitle: 'Clusters',
    globalOnly: false,
  },
  { action: 'queue:purge', label: 'Purge queues', featureId: 'queues', featureTitle: 'Queues', globalOnly: false },
];

const DAY = 86_400_000;

function token(over: Record<string, unknown> = {}) {
  return {
    id: 't1',
    name: 'laptop',
    owner: 'ada',
    prefix: 'as_abcd',
    expiresAt: new Date(Date.now() + 30 * DAY).toISOString(),
    lastUsedAt: null,
    revokedAt: null,
    createdAt: new Date().toISOString(),
    previousValidUntil: null,
    grants: [],
    mcpTools: [],
    stale: false,
    ...over,
  };
}

function mockBaseApis(grants: unknown[], tokens: unknown[] = []) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({ id: 'u1', username: 'ada', mustChangePassword: false, grants }),
    ),
    http.get('*/api/v1/tokens', () => HttpResponse.json(tokens)),
    http.get('*/api/v1/tokens/policy', () =>
      HttpResponse.json({
        maxLifetime: 'PT2160H',
        latestExpiry: new Date(Date.now() + 90 * DAY).toISOString(),
        rotationOverlap: 'PT24H',
      }),
    ),
    http.get('*/api/v1/mcp/tools', () =>
      HttpResponse.json([
        { name: 'list_resources', posture: 'READ', summary: 'List resources' },
        { name: 'queue_lifecycle', posture: 'MUTATE', summary: 'Create, purge or delete a queue' },
      ]),
    ),
    http.get('*/api/v1/permissions', () => HttpResponse.json(CATALOGUE)),
    http.get('*/api/v1/clusters', () => HttpResponse.json([])),
  );
}

describe('ApiKeysPanel', () => {
  it('lists existing keys with their expiry and tool restriction', async () => {
    mockBaseApis([], [token({ mcpTools: ['list_resources'] })]);
    renderWithProviders(<ApiKeysPanel />);

    expect(await screen.findByText('laptop')).toBeInTheDocument();
    expect(screen.getByText('as_abcd')).toBeInTheDocument();
    expect(screen.getByText('list_resources')).toBeInTheDocument();
  });

  it('teaches what a key is when there are none', async () => {
    mockBaseApis([]);
    renderWithProviders(<ApiKeysPanel />);

    expect(await screen.findByText(/You have no keys/)).toBeInTheDocument();
  });

  it('says so when the keys cannot be loaded', async () => {
    mockBaseApis([]);
    server.use(
      http.get('*/api/v1/tokens', () =>
        HttpResponse.json({ title: 'Boom', detail: 'Database unavailable' }, { status: 500 }),
      ),
    );
    renderWithProviders(<ApiKeysPanel />);

    expect(await screen.findByText('Your keys could not be loaded')).toBeInTheDocument();
  });

  it('offers only the permissions the user holds', async () => {
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    const user = userEvent.setup();
    renderWithProviders(<ApiKeysPanel />);

    await user.click(await screen.findByRole('button', { name: 'New key' }));

    expect(await screen.findByRole('button', { name: /Clusters, 0 of 1 selected/ })).toBeInTheDocument();
    // Offering a permission the server would strip out would mint a key that
    // silently does less than the operator ticked.
    expect(screen.queryByRole('button', { name: /Queues/ })).not.toBeInTheDocument();
  });

  it('posts the chosen grants, an expiry within the cap and the tools, and shows the value once', async () => {
    let posted: { grants?: unknown[]; expiresAt?: string; mcpTools?: string[] } = {};
    mockBaseApis([{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }]);
    server.use(
      http.post('*/api/v1/tokens', async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json(
          { token: token({ id: 't9', name: 'agent' }), value: 'as_zzzz_secret-value' },
          { status: 201 },
        );
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApiKeysPanel />);

    await user.click(await screen.findByRole('button', { name: 'New key' }));
    await user.type(await screen.findByRole('textbox', { name: /Name/ }), 'agent');
    await user.click(await screen.findByRole('checkbox', { name: 'Select all in Clusters' }));
    await user.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByDisplayValue('as_zzzz_secret-value')).toBeInTheDocument();
    expect(posted.grants).toEqual([{ action: 'cluster:read', scopeType: 'GLOBAL', scopeId: null }]);
    expect(posted.mcpTools).toEqual([]);
    const days = (Date.parse(posted.expiresAt!) - Date.now()) / DAY;
    expect(days).toBeGreaterThan(29);
    expect(days).toBeLessThanOrEqual(30);
  });

  it('rotates a key and says until when the old secret works', async () => {
    const overlapEnds = new Date(Date.now() + DAY).toISOString();
    mockBaseApis([], [token()]);
    server.use(
      http.post('*/api/v1/tokens/t1/rotate', () =>
        HttpResponse.json({ token: token({ previousValidUntil: overlapEnds }), value: 'as_new_secret' }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApiKeysPanel />);

    await user.click(await screen.findByRole('button', { name: 'Rotate laptop' }));
    await user.click(await screen.findByRole('button', { name: 'Rotate' }));

    expect(await screen.findByDisplayValue('as_new_secret')).toBeInTheDocument();
    expect(screen.getByText(/The old secret keeps working until/)).toBeInTheDocument();
  });

  it('revokes a key only once its name is typed', async () => {
    let revoked = false;
    mockBaseApis([], [token()]);
    server.use(
      http.delete('*/api/v1/tokens/t1', () => {
        revoked = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApiKeysPanel />);

    await user.click(await screen.findByRole('button', { name: 'Revoke laptop' }));
    const confirm = await screen.findByRole('button', { name: 'Revoke key' });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: /Type "laptop" to confirm/ }), 'laptop');
    await user.click(confirm);

    await expect.poll(() => revoked).toBe(true);
  });

  it('shows a key’s usage per day', async () => {
    mockBaseApis([], [token()]);
    server.use(
      http.get('*/api/v1/tokens/t1/usage', () =>
        HttpResponse.json({
          days: 7,
          requests: 42,
          denied: 3,
          limited: 1,
          errors: 0,
          perDay: [{ day: '2026-09-30', requests: 42, denied: 3, limited: 1, errors: 0 }],
        }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ApiKeysPanel />);

    await user.click(await screen.findByRole('button', { name: 'Usage of laptop' }));

    expect(await screen.findByText('2026-09-30')).toBeInTheDocument();
    expect(screen.getAllByText('42').length).toBeGreaterThan(0);
  });
});
