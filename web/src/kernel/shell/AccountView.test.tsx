import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../api/paging.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ to, children }: { to: string; children?: ReactNode }) => <a href={to}>{children}</a>,
}));

// The code-highlight adapter is a lazy shiki import; under jsdom the plain
// fallback is what renders, which is all this page's assertions need.
const { AccountView } = await import('./AccountView.tsx');

function mockAccountApis() {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'ada',
        mustChangePassword: false,
        providerId: 'local',
        secondFactorEnrolmentRequired: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }],
        reauthentication: {
          method: 'PASSWORD',
          startPath: null,
          authenticatedAt: new Date().toISOString(),
          windowSeconds: 300,
        },
      }),
    ),
    http.get('*/api/v1/auth/mfa', () =>
      HttpResponse.json({
        passwordAccount: true,
        required: false,
        enrolled: false,
        totpEnrolled: false,
        recoveryCodesRemaining: 0,
        webauthn: { available: true, reason: null },
        passkeys: [],
        trustedDevices: [],
      }),
    ),
    http.get('*/api/v1/auth/sessions', () =>
      HttpResponse.json(
        paged([
          {
            handle: 'a'.repeat(32),
            signedInAt: new Date().toISOString(),
            lastActivityAt: new Date().toISOString(),
            clientAddress: '203.0.113.7',
            userAgent: null,
            current: true,
          },
        ]),
      ),
    ),
    http.get('*/api/v1/tokens', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/permissions', () =>
      HttpResponse.json(paged([{ action: 'cluster:read', label: 'Read clusters' }])),
    ),
    http.get('*/api/v1/clusters', () => HttpResponse.json(paged([]))),
  );
}

describe('AccountView', () => {
  it('renders every section of the page', async () => {
    mockAccountApis();
    renderWithProviders(<AccountView />);

    expect(await screen.findByText('ada')).toBeInTheDocument();
    // Two-step verification sits right after the password, before the sessions it protects.
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual(['Account']);
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(headings.slice(1, 4)).toEqual(['Password', 'Two-step verification', 'Sessions']);
    expect(await screen.findByText('Two-step verification is off')).toBeInTheDocument();
    expect(await screen.findByText('This session')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'API keys' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'MCP connection' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Change password' })).toHaveAttribute('href', '/change-password');
  });

  it('shows the MCP endpoint and never a real key', async () => {
    mockAccountApis();
    renderWithProviders(<AccountView />);

    const endpoint = await screen.findByLabelText<HTMLInputElement>('Endpoint');
    expect(endpoint.value).toMatch(/\/mcp$/);
    // A placeholder, not a credential: this page is reachable long after the one
    // moment a key's value exists.
    expect(screen.getAllByText(/<your-api-key>/).length).toBeGreaterThan(0);
  });
});
