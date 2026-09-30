import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
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

const problem = (slug: string, status: number) =>
  HttpResponse.json({ type: `https://artemis-studio.dev/problems/${slug}`, title: slug }, { status });

const me = (authenticatedAt = new Date().toISOString()) =>
  http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'root',
      mustChangePassword: false,
      secondFactorEnrolmentRequired: false,
      grants: [],
      reauthentication: { method: 'PASSWORD', startPath: null, authenticatedAt, windowSeconds: 300 },
    }),
  );

describe('UsersPanel two-step verification', () => {
  it("says each user's second step in words, and highlights a required one that is missing", async () => {
    serveUsers({
      users: [
        { ...user('alice', null), secondFactors: ['TOTP', 'WEBAUTHN'] },
        { ...user('bob', null) },
        { ...user('carol', null), secondFactorRequired: true },
        { ...user('dave', null), providerId: 'okta' },
      ],
    });

    renderWithProviders(<UsersPanel />);

    expect(await screen.findByText('Authenticator app, Passkey')).toBeInTheDocument();
    expect(screen.getByText('Not set up')).toBeInTheDocument();
    expect(screen.getByText('Required, not set up')).toBeInTheDocument();
    expect(screen.getByText('Managed by their identity provider')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reset two-step verification of alice' })).toHaveAccessibleDescription(
      'Authenticator app, Passkey',
    );
    // Nothing to reset, nothing offered: the status already says why.
    expect(screen.getAllByRole('button', { name: /^Reset two-step verification of / })).toHaveLength(1);
  });

  it('arms the reset only when the username is typed, states its reach, and announces the result', async () => {
    const state: { users: UserView[] } = { users: [{ ...user('alice', null), secondFactors: ['TOTP'] }] };
    serveUsers(state);
    let reset = false;
    server.use(
      me(),
      http.delete('*/api/v1/users/id-alice/second-factors', () => {
        reset = true;
        state.users = [user('alice', null)];
        return HttpResponse.json(state.users[0]);
      }),
    );
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);

    const trigger = await screen.findByRole('button', { name: 'Reset two-step verification of alice' });
    await person.click(trigger);

    const dialog = await screen.findByRole('dialog', { name: 'Reset two-step verification of alice' });
    expect(dialog).toHaveTextContent(
      "removes alice's authenticator app, passkeys, recovery codes and trusted devices, revokes their API keys and signs them out everywhere",
    );
    const confirm = within(dialog).getByRole('button', { name: 'Reset two-step verification' });
    expect(confirm).toBeDisabled();
    await person.type(within(dialog).getByLabelText('Type "alice" to confirm'), 'alic');
    expect(confirm).toBeDisabled();
    await person.type(within(dialog).getByLabelText('Type "alice" to confirm'), 'e');
    expect(confirm).toBeEnabled();
    await person.click(confirm);

    await waitFor(() => expect(reset).toBe(true));
    const outcome = await screen.findByText('Reset two-step verification of alice.');
    expect(outcome.closest('[aria-live="polite"]')).not.toBeNull();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Not set up')).toBeInTheDocument();
  });

  it('is done by keyboard alone: focus enters the dialog, Escape leaves it, focus returns to the button', async () => {
    serveUsers({ users: [{ ...user('alice', null), secondFactors: ['TOTP'] }] });
    server.use(me());
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);

    const trigger = await screen.findByRole('button', { name: 'Reset two-step verification of alice' });
    trigger.focus();
    await person.keyboard('{Enter}');

    const dialog = await screen.findByRole('dialog', { name: 'Reset two-step verification of alice' });
    expect(dialog).toContainElement(document.activeElement as HTMLElement);

    await person.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it('asks for a fresh sign-in inside the dialog when the server says so', async () => {
    serveUsers({ users: [{ ...user('alice', null), secondFactors: ['TOTP'] }] });
    server.use(
      me(new Date(Date.now() - 3_600_000).toISOString()),
      http.delete('*/api/v1/users/id-alice/second-factors', () => problem('reauthentication-required', 403)),
    );
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);

    await person.click(await screen.findByRole('button', { name: 'Reset two-step verification of alice' }));
    const dialog = await screen.findByRole('dialog');
    await person.type(within(dialog).getByLabelText('Type "alice" to confirm'), 'alice');
    await person.click(within(dialog).getByRole('button', { name: 'Reset two-step verification' }));

    expect(await within(dialog).findByLabelText('Your password')).toBeInTheDocument();
  });

  it.each([
    ['self-reset', 409, /cannot reset your own two-step verification here\. Sign in with one of your recovery codes/],
    ['mfa-required', 403, /your own session has to have verified one\. Sign out, sign in with your second factor/],
  ])('says what a refused reset (%s) means and what to do', async (slug, status, advice) => {
    serveUsers({ users: [{ ...user('alice', null), secondFactors: ['TOTP'] }] });
    server.use(
      me(),
      http.delete('*/api/v1/users/id-alice/second-factors', () => problem(slug, status)),
    );
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);

    await person.click(await screen.findByRole('button', { name: 'Reset two-step verification of alice' }));
    const dialog = await screen.findByRole('dialog');
    await person.type(within(dialog).getByLabelText('Type "alice" to confirm'), 'alice');
    await person.click(within(dialog).getByRole('button', { name: 'Reset two-step verification' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(advice);
  });
});
