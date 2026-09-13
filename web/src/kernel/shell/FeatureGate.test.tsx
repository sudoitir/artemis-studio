import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';

import { manifestHandler } from '../../test/manifest.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}));

const { FeatureGate } = await import('./FeatureGate.tsx');

describe('FeatureGate', () => {
  it('explains a disabled feature at its address and names the property that enables it', async () => {
    server.use(manifestHandler(['sql']));
    renderWithProviders(
      <FeatureGate feature="sql">
        <p>console</p>
      </FeatureGate>,
    );

    expect(await screen.findByRole('heading', { name: 'sql is disabled on this installation' })).toBeInTheDocument();
    expect(screen.getByText('artemis-studio.features.sql.enabled=true')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to the cluster' })).toHaveAttribute('href', '/clusters/c1');
    expect(screen.queryByText('console')).not.toBeInTheDocument();
  });

  it("renders an enabled feature's view", async () => {
    server.use(manifestHandler());
    renderWithProviders(
      <FeatureGate feature="sql">
        <p>console</p>
      </FeatureGate>,
    );

    expect(await screen.findByText('console')).toBeInTheDocument();
  });
});
