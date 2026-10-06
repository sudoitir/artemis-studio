import { afterEach, describe, expect, it } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Notifications, notifications } from '@mantine/notifications';

import { holding } from '../../test/access.ts';
import { server } from '../../test/setup.ts';
import { renderWithProviders } from '../../test/render.tsx';
import type { UserView } from './api.ts';
import { UsersPanel } from './UsersPanel.tsx';
import { paged } from '../../kernel/api/paging.ts';

function renderUsers() {
  return renderWithProviders(
    <>
      <Notifications />
      <UsersPanel />
    </>,
  );
}

afterEach(() => act(() => notifications.clean()));

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
  passwordAccount: true,
  grants: [],
});

const CLUSTER = {
  id: 'c-prod',
  name: 'prod',
  health: 'OK',
  nodeCount: 2,
  updatedAt: '2026-10-01T00:00:00Z',
  environmentId: 'e-live',
};
const ENVIRONMENT = { id: 'e-live', name: 'Live', colour: null, sortOrder: 1 };

function serveUsers(state: { users: UserView[] }, roles: unknown[] = []) {
  server.use(
    http.get('*/api/v1/users', () => HttpResponse.json(paged(state.users))),
    http.get('*/api/v1/roles', () => HttpResponse.json(paged(roles))),
    http.get('*/api/v1/clusters', () => HttpResponse.json(paged([CLUSTER]))),
    http.get('*/api/v1/environments', () => HttpResponse.json(paged([ENVIRONMENT]))),
  );
}

// jsdom has no layout, so Mantine keeps a select's list `display: none`: options are queried with `hidden`.
const option = (name: string) => screen.findByRole('option', { name, hidden: true });

describe('UsersPanel account lock', () => {
  it('says in words that an account is locked and offers Unlock only there', async () => {
    serveUsers({ users: [user('alice', LOCK_LIFTS_AT), user('bob', null)] });

    renderUsers();

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
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'Unlock alice' }));

    // In flight: announced, and the control cannot be submitted again.
    expect(await screen.findByRole('status')).toHaveTextContent('Unlocking alice…');
    expect(screen.getByRole('button', { name: 'Unlock alice' })).toBeDisabled();
    release();

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Unlocked alice'));
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
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'Unlock alice' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not unlock alice');
    expect(alert).toHaveTextContent('Access denied. The account is still locked. Try again.');
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
        { ...user('dave', null), providerId: 'okta', passwordAccount: false },
      ],
    });

    renderUsers();

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

  it("treats a plugin sign-in's user like a local one: its required second step is highlighted", async () => {
    serveUsers({
      users: [{ ...user('erin', null), providerId: 'acme:corp', secondFactorRequired: true }],
    });

    renderUsers();

    expect(await screen.findByText('Required, not set up')).toBeInTheDocument();
    expect(screen.queryByText('Managed by their identity provider')).not.toBeInTheDocument();
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
    renderUsers();

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
    expect(await screen.findByRole('status')).toHaveTextContent('Reset two-step verification of alice');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Not set up')).toBeInTheDocument();
  });

  it('is done by keyboard alone: focus enters the dialog, Escape leaves it, focus returns to the button', async () => {
    serveUsers({ users: [{ ...user('alice', null), secondFactors: ['TOTP'] }] });
    server.use(me());
    const person = userEvent.setup();
    renderUsers();

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
    renderUsers();

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
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'Reset two-step verification of alice' }));
    const dialog = await screen.findByRole('dialog');
    await person.type(within(dialog).getByLabelText('Type "alice" to confirm'), 'alice');
    await person.click(within(dialog).getByRole('button', { name: 'Reset two-step verification' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(advice);
    // The dialog stays open, so the operator can act on what it says.
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

const withGrant = (name: string): UserView => ({
  ...user(name, null),
  grants: [{ roleId: 'r-viewer', roleName: 'VIEWER', scopeType: 'GLOBAL', scopeId: null }],
});

describe('UsersPanel roles and accounts', () => {
  it('removes a role only once the username is typed, says what is lost, and announces it', async () => {
    const state = { users: [withGrant('alice')] };
    serveUsers(state);
    let removed = false;
    server.use(
      http.delete('*/api/v1/users/id-alice/grants/r-viewer', () => {
        removed = true;
        state.users = [user('alice', null)];
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'Remove VIEWER from alice' }));
    const dialog = await screen.findByRole('dialog', { name: 'Remove VIEWER from alice' });
    expect(dialog).toHaveTextContent('alice loses the permissions that VIEWER gave them');
    const confirm = within(dialog).getByRole('button', { name: 'Remove role' });
    expect(confirm).toBeDisabled();
    await person.type(within(dialog).getByLabelText('Type "alice" to confirm'), 'alice');
    await person.click(confirm);

    await waitFor(() => expect(removed).toBe(true));
    expect(await screen.findByRole('status')).toHaveTextContent('Removed VIEWER from alice');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove VIEWER from alice' })).toBeNull());
  });

  it('disables an account, announces it, and says why and what to do when that fails', async () => {
    serveUsers({ users: [user('alice', null)] });
    server.use(
      http.put('*/api/v1/users/id-alice/disabled', () =>
        HttpResponse.json({ title: 'Boom', detail: 'The database is down.' }, { status: 500 }),
      ),
    );
    const person = userEvent.setup();
    renderUsers();

    await person.click(await screen.findByRole('switch', { name: 'Disable alice' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not disable alice');
    expect(alert).toHaveTextContent('The database is down. The account is still enabled. Try again.');
  });

  it('asks which role to grant on submit, beside the field, and puts focus there', async () => {
    serveUsers({ users: [user('alice', null)] });
    const person = userEvent.setup();
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'Grant a role to alice' }));
    const dialog = await screen.findByRole('dialog', { name: 'Grant a role to alice' });
    await person.click(within(dialog).getByRole('button', { name: 'Grant' }));

    expect(await within(dialog).findByText('Choose the role to grant.')).toBeInTheDocument();
    expect(within(dialog).getByRole('combobox', { name: /Role/ })).toHaveFocus();
  });

  it('asks for the username and the password on submit, focusing the first missing one', async () => {
    serveUsers({ users: [] });
    const person = userEvent.setup();
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'New user' }));
    const dialog = await screen.findByRole('dialog', { name: 'New user' });
    await person.click(within(dialog).getByRole('button', { name: 'Create' }));

    expect(await within(dialog).findByText('Enter the username they sign in with.')).toBeInTheDocument();
    expect(within(dialog).getByText('Enter an initial password.')).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: /Username/ })).toHaveFocus();
  });

  it('teaches what a user is when there are none', async () => {
    serveUsers({ users: [] });
    renderUsers();
    expect(await screen.findByText('No users')).toBeInTheDocument();
  });

  it('states a failed load with its cause and offers a retry', async () => {
    server.use(
      http.get('*/api/v1/users', () =>
        HttpResponse.json({ title: 'Boom', detail: 'The database is down.' }, { status: 500 }),
      ),
    );
    renderUsers();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The database is down.');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});

describe('UsersPanel grant scope', () => {
  const VIEWER = { id: 'r-viewer', name: 'VIEWER', builtin: true, permissions: ['*'], requiresMfa: false };

  it('names the environment or cluster a grant applies to', async () => {
    server.use(holding('environment:read'));
    const scoped = (grants: UserView['grants']): UserView => ({ ...user('alice', null), grants });
    serveUsers({
      users: [
        scoped([
          { roleId: 'r-viewer', roleName: 'VIEWER', scopeType: 'CLUSTER', scopeId: 'c-prod' },
          { roleId: 'r-viewer', roleName: 'VIEWER', scopeType: 'ENVIRONMENT', scopeId: 'e-live' },
          { roleId: 'r-viewer', roleName: 'VIEWER', scopeType: 'GLOBAL', scopeId: null },
        ]),
      ],
    });
    renderUsers();

    expect(await screen.findByRole('button', { name: 'Remove VIEWER (Cluster prod) from alice' })).toBeInTheDocument();
    expect(
      await screen.findByRole('button', { name: 'Remove VIEWER (Environment Live) from alice' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove VIEWER from alice' })).toBeInTheDocument();
  });

  it('grants a role on one cluster', async () => {
    serveUsers({ users: [user('alice', null)] }, [VIEWER]);
    let body: unknown;
    server.use(
      http.post('*/api/v1/users/id-alice/grants', async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'Grant a role to alice' }));
    const dialog = await screen.findByRole('dialog', { name: 'Grant a role to alice' });
    await person.click(within(dialog).getByRole('combobox', { name: /Role/ }));
    await person.click(await option('VIEWER'));
    await person.click(within(dialog).getByRole('combobox', { name: 'Scope' }));
    await person.click(await option('Cluster'));
    await person.click(within(dialog).getByRole('combobox', { name: /Cluster/ }));
    await person.click(await option('prod'));
    await person.click(within(dialog).getByRole('button', { name: 'Grant' }));

    await waitFor(() => expect(body).toEqual({ roleId: 'r-viewer', scopeType: 'CLUSTER', scopeId: 'c-prod' }));
  });

  it('grants globally by default, with no scope id', async () => {
    serveUsers({ users: [user('alice', null)] }, [VIEWER]);
    let body: unknown;
    server.use(
      http.post('*/api/v1/users/id-alice/grants', async ({ request }) => {
        body = await request.json();
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'Grant a role to alice' }));
    const dialog = await screen.findByRole('dialog', { name: 'Grant a role to alice' });
    await person.click(within(dialog).getByRole('combobox', { name: /Role/ }));
    await person.click(await option('VIEWER'));
    await person.click(within(dialog).getByRole('button', { name: 'Grant' }));

    await waitFor(() => expect(body).toEqual({ roleId: 'r-viewer', scopeType: 'GLOBAL' }));
  });

  it('asks which environment when the scope is one, and sends nothing meanwhile', async () => {
    serveUsers({ users: [user('alice', null)] }, [VIEWER]);
    let posted = false;
    server.use(
      http.post('*/api/v1/users/id-alice/grants', () => {
        posted = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderUsers();

    await person.click(await screen.findByRole('button', { name: 'Grant a role to alice' }));
    const dialog = await screen.findByRole('dialog', { name: 'Grant a role to alice' });
    await person.click(within(dialog).getByRole('combobox', { name: /Role/ }));
    await person.click(await option('VIEWER'));
    await person.click(within(dialog).getByRole('combobox', { name: 'Scope' }));
    await person.click(await option('Environment'));
    await person.click(within(dialog).getByRole('button', { name: 'Grant' }));

    expect(await within(dialog).findByText('Choose the environment the role applies to.')).toBeInTheDocument();
    expect(within(dialog).getByRole('combobox', { name: /Environment/ })).toHaveFocus();
    expect(posted).toBe(false);
  });
});
