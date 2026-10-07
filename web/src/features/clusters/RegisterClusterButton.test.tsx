import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { paged } from '../../kernel/api/paging.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useNavigate: () => vi.fn(),
}));

const { RegisterClusterButton } = await import('./RegisterClusterButton.tsx');

describe('RegisterClusterButton', () => {
  it('opens the form in a dialog, loaded on demand', async () => {
    server.use(
      http.get('*/api/v1/clusters', () =>
        HttpResponse.json(
          paged([{ id: 'c1', name: 'prod-emea', health: 'OK', nodeCount: 2, updatedAt: '2026-01-01T00:00:00Z' }]),
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<RegisterClusterButton />);

    await user.click(screen.getByRole('button', { name: 'Register cluster' }));

    const dialog = await screen.findByRole('dialog', { name: 'Register cluster' });
    expect(await screen.findByLabelText('Broker management URL')).toBeInTheDocument();
    expect(dialog).toContainElement(screen.getByLabelText('Broker management URL'));
  });
});
