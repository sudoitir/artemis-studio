import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../test/setup.ts';
import { renderWithProviders } from '../../test/render.tsx';
import type { UserView } from './api.ts';
import { UsersPanel } from './UsersPanel.tsx';

const LOCK_LIFTS_AT = new Date(Date.now() + 10 * 60_000).toISOString();

const user = (username: string, lockedUntil: string | null): UserView => ({
  id: `id-${username}`,
  username,
  email: null,
  providerId: 'local',
  disabled: false,
  mustChangePassword: false,
  lockedUntil,
  secondFactors: [],
  secondFactorRequired: false,
  grants: [],
});

function serveUsers(state: { users: UserView[] }) {
  server.use(
    http.get('*/api/v1/users', () => HttpResponse.json(state.users)),
    http.get('*/api/v1/roles', () => HttpResponse.json([])),
  );
}

describe('UsersPanel account lock', () => {
  it('says in words that an account is locked and offers Unlock only there', async () => {
    serveUsers({ users: [user('alice', LOCK_LIFTS_AT), user('bob', null)] });

    renderWithProviders(<UsersPanel />);

    expect(await screen.findByText(/^Locked until /)).toBeInTheDocument();
    expect(screen.getAllByText(/^Locked until /)).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Unlock alice' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Unlock bob' })).not.toBeInTheDocument();
  });

  it('unlocks, announces it, and the badge goes away', async () => {
    const state = { users: [user('alice', LOCK_LIFTS_AT)] };
    serveUsers(state);
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    server.use(
      http.put('*/api/v1/users/id-alice/unlock', async () => {
        await gate;
        state.users = [user('alice', null)];
        return HttpResponse.json(state.users[0]);
      }),
    );
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);

    await person.click(await screen.findByRole('button', { name: 'Unlock alice' }));

    // In flight: announced, and the control cannot be submitted again.
    expect(await screen.findByText('Unlocking alice…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unlock alice' })).toBeDisabled();
    release();

    await waitFor(() => expect(screen.getByText('Unlocked alice.')).toBeInTheDocument());
    expect(screen.getByText('Unlocked alice.').closest('[aria-live="polite"]')).not.toBeNull();
    await waitFor(() => expect(screen.queryByText(/^Locked until /)).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Unlock alice' })).not.toBeInTheDocument();
  });

  it('says why an unlock failed and keeps the account shown as locked', async () => {
    serveUsers({ users: [user('alice', LOCK_LIFTS_AT)] });
    server.use(
      http.put('*/api/v1/users/id-alice/unlock', () =>
        HttpResponse.json({ title: 'Forbidden', detail: 'Access denied.' }, { status: 403 }),
      ),
    );
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);

    await person.click(await screen.findByRole('button', { name: 'Unlock alice' }));

    const outcome = await screen.findByText('Could not unlock alice. Access denied. Try again.');
    expect(outcome.closest('[aria-live="polite"]')).not.toBeNull();
    expect(screen.getByText(/^Locked until /)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unlock alice' })).toBeEnabled();
  });
});
