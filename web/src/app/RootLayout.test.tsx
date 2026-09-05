import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({}),
  useNavigate: () => () => {},
  useLocation: () => ({ pathname: '/' }),
  Outlet: () => null,
  Link: ({
    to,
    children,
    className,
    'aria-label': ariaLabel,
  }: {
    to: string;
    children?: ReactNode;
    className?: string;
    'aria-label'?: string;
  }) => (
    <a href={to} className={className} aria-label={ariaLabel}>
      {children}
    </a>
  ),
}));

const { RootLayout } = await import('./RootLayout.tsx');

// CommandPalette (mounted by RootLayout) always queries a queues endpoint,
// even with no active cluster — a pre-existing behavior, not introduced here.
function mockEmptyQueues() {
  server.use(
    http.get(/\/api\/v1\/clusters\/.*\/queues/, () =>
      HttpResponse.json({ data: [], count: 0, page: 1, pageSize: 50 }),
    ),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json([])),
  );
}

// RootLayout gates its shell behind `/auth/me` (identity-and-sessions spec).
function mockAuthenticated() {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'test-user',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
      }),
    ),
  );
}

describe('RootLayout sidebar collapse', () => {
  it('persists the collapse toggle to localStorage and restores it on remount without a flash', async () => {
    mockAuthenticated();
    mockEmptyQueues();
    server.use(http.get('*/api/v1/clusters', () => HttpResponse.json([])));
    const user = userEvent.setup();
    localStorage.removeItem('as:nav:collapsed');

    const { unmount } = renderWithProviders(<RootLayout />);
    const toggle = await screen.findByRole('button', { name: 'Collapse sidebar' });
    await user.click(toggle);

    expect(localStorage.getItem('as:nav:collapsed')).toBe('true');
    unmount();

    renderWithProviders(<RootLayout />);
    expect(await screen.findByRole('button', { name: 'Expand sidebar' })).toBeInTheDocument();
  });

  it('keeps collapsed cluster rows reachable by name for a screen reader', async () => {
    mockAuthenticated();
    mockEmptyQueues();
    server.use(
      http.get('*/api/v1/clusters', () =>
        HttpResponse.json([{ id: 'c1', name: 'prod-emea', health: 'OK', nodeCount: 3 }]),
      ),
    );
    localStorage.setItem('as:nav:collapsed', 'true');
    renderWithProviders(<RootLayout />);

    expect(await screen.findByRole('link', { name: /prod-emea/ })).toBeInTheDocument();
  });
});
