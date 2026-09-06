import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

vi.mock('@tanstack/react-router', () => ({
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
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['cluster:read'] }],
      }),
    ),
    http.get('*/api/v1/tokens', () => HttpResponse.json([])),
    http.get('*/api/v1/permissions', () =>
      HttpResponse.json([{ action: 'cluster:read', label: 'Read clusters' }]),
    ),
    http.get('*/api/v1/clusters', () => HttpResponse.json([])),
  );
}

describe('AccountView', () => {
  it('renders every section of the page', async () => {
    mockAccountApis();
    renderWithProviders(<AccountView />);

    expect(await screen.findByText('ada')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'API keys' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'MCP connection' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Change password' })).toHaveAttribute(
      'href',
      '/change-password',
    );
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
