import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';
import { EnvironmentsPanel } from './EnvironmentsPanel.tsx';

function env(over: Record<string, unknown> = {}) {
  return { id: 'e1', name: 'production', colour: '#e03131', sortOrder: 0, ...over };
}

describe('EnvironmentsPanel', () => {
  it('lists existing environments', async () => {
    server.use(http.get('*/api/v1/environments', () => HttpResponse.json([env()])));
    renderWithProviders(<EnvironmentsPanel />);

    expect(await screen.findByText('production')).toBeInTheDocument();
    expect(screen.getByText('1 environment')).toBeInTheDocument();
  });

  it('shows a count for zero environments', async () => {
    server.use(http.get('*/api/v1/environments', () => HttpResponse.json([])));
    renderWithProviders(<EnvironmentsPanel />);

    expect(await screen.findByText('0 environments')).toBeInTheDocument();
  });

  it('creates an environment from the form', async () => {
    let created = false;
    server.use(
      http.get('*/api/v1/environments', () => HttpResponse.json(created ? [env()] : [])),
      http.post('*/api/v1/environments', () => {
        created = true;
        return HttpResponse.json(env(), { status: 201 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnvironmentsPanel />);

    await screen.findByText('0 environments');
    await user.click(screen.getByRole('button', { name: 'New environment' }));
    await user.type(await screen.findByLabelText(/Name/), 'production');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByText('production')).toBeInTheDocument();
  });

  it('deletes an environment', async () => {
    let deleted = false;
    server.use(
      http.get('*/api/v1/environments', () => HttpResponse.json(deleted ? [] : [env()])),
      http.delete('*/api/v1/environments/e1', () => {
        deleted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<EnvironmentsPanel />);

    await screen.findByText('production');
    await user.click(screen.getByRole('button', { name: 'Delete production' }));

    await screen.findByText('0 environments');
  });
});
