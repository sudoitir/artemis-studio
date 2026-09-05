import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const { ClusterViewNav } = await import('./ClusterViewNav.tsx');

describe('ClusterViewNav', () => {
  it('shows the firing count badge on the Alerts item when this cluster has open firings', async () => {
    server.use(
      http.get('*/api/v1/alerts/firing', () =>
        HttpResponse.json([
          { clusterId: 'c1', firing: 3 },
          { clusterId: 'c2', firing: 1 },
        ]),
      ),
    );
    renderWithProviders(<ClusterViewNav clusterId="c1" collapsed={false} />);

    expect(await screen.findByText('3')).toBeInTheDocument();
  });

  it('shows no badge when this cluster has nothing firing', async () => {
    server.use(
      http.get('*/api/v1/alerts/firing', () => HttpResponse.json([{ clusterId: 'c2', firing: 1 }])),
    );
    renderWithProviders(<ClusterViewNav clusterId="c1" collapsed={false} />);

    await screen.findByText('Alerts');
    expect(screen.queryByText('1')).not.toBeInTheDocument();
  });
});
