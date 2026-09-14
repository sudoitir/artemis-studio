import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';

import { manifestHandler } from '../../test/manifest.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  Link: ({ children, to, 'aria-label': ariaLabel }: { children: ReactNode; to: string; 'aria-label'?: string }) => (
    <a href={to} aria-label={ariaLabel}>
      {children}
    </a>
  ),
}));

const { ClusterViewNav } = await import('./ClusterViewNav.tsx');

function mockApi({
  disabled = [],
  permissions = ['*'],
  firing = [],
}: {
  disabled?: string[];
  permissions?: string[];
  firing?: { clusterId: string; firing: number }[];
} = {}) {
  server.use(
    manifestHandler(disabled),
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'operator',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/alerts/firing', () => HttpResponse.json(firing)),
  );
}

describe('ClusterViewNav', () => {
  it('lists views under their group headings, in the fixed group order', async () => {
    mockApi();
    renderWithProviders(<ClusterViewNav clusterId="c1" collapsed={false} />);

    await screen.findByRole('link', { name: 'Topology' });
    expect(screen.getAllByRole('heading').map((heading) => heading.textContent)).toEqual([
      'Observe',
      'Messaging',
      'Resources',
      'Configuration',
      'Activity',
    ]);
    const messaging = screen.getByRole('group', { name: 'Messaging' });
    expect(within(messaging).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Queues',
      'DLQ',
      'SQL Console',
    ]);
  });

  it("leaves out a disabled feature's views, and a group left with none", async () => {
    mockApi({ disabled: ['sql', 'events', 'audit'] });
    renderWithProviders(<ClusterViewNav clusterId="c1" collapsed={false} />);

    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Activity' })).not.toBeInTheDocument());
    expect(screen.queryByRole('link', { name: 'SQL Console' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Queues' })).toBeInTheDocument();
  });

  it('keeps a view the operator cannot read listed and disabled, with the reason', async () => {
    mockApi({ permissions: ['cluster:read'] });
    renderWithProviders(<ClusterViewNav clusterId="c1" collapsed={false} />);

    const alerts = await screen.findByRole('link', { name: 'Alerts', description: /alert:read/ });
    expect(alerts).toHaveAttribute('aria-disabled', 'true');
    // Focusable, so the reason is reachable by keyboard.
    expect(alerts).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('link', { name: 'Queues' })).not.toHaveAttribute('aria-disabled');
  });

  it('keeps each group and view named when the nav is collapsed', async () => {
    mockApi();
    renderWithProviders(<ClusterViewNav clusterId="c1" collapsed />);

    expect(await screen.findByRole('group', { name: 'Observe' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Topology' })).toBeInTheDocument();
  });

  it('shows the firing count badge on the Alerts item when this cluster has open firings', async () => {
    mockApi({
      firing: [
        { clusterId: 'c1', firing: 3 },
        { clusterId: 'c2', firing: 1 },
      ],
    });
    renderWithProviders(<ClusterViewNav clusterId="c1" collapsed={false} />);

    expect(await screen.findByText('3')).toBeInTheDocument();
  });

  it('shows no badge when this cluster has nothing firing', async () => {
    mockApi({ firing: [{ clusterId: 'c2', firing: 1 }] });
    renderWithProviders(<ClusterViewNav clusterId="c1" collapsed={false} />);

    await screen.findByRole('link', { name: 'Alerts' });
    expect(screen.queryByText('1')).not.toBeInTheDocument();
  });
});
