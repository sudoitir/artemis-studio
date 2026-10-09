import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Notifications, notifications } from '@mantine/notifications';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { TeamMembers } from './TeamMembers.tsx';
import { choose, serveLookups, team, TEAM_OPERATOR, TEAM_VIEWER } from '../../test/teams.ts';
import { holdButton } from '../../test/hold.ts';

afterEach(() => act(() => notifications.clean()));

function serve(permissions: string[]) {
  serveLookups(permissions);
  server.use(
    http.get('*/api/v1/auth/providers', () =>
      HttpResponse.json(
        paged([
          { id: 'local', label: 'Local' },
          { id: 'okta', label: 'Okta' },
        ]),
      ),
    ),
  );
}

function renderMembers() {
  return renderWithProviders(
    <>
      <Notifications />
      <TeamMembers team={team()} />
    </>,
  );
}

describe('TeamMembers', () => {
  it('lists users and groups with the team role each holds', async () => {
    serve(['user:admin']);
    renderMembers();

    const alice = await screen.findByRole('row', { name: /alice/ });
    await waitFor(() =>
      expect(within(alice).getByRole('combobox', { name: 'Role of alice' })).toHaveValue('Team Operator'),
    );
    const group = screen.getByRole('row', { name: /orders-ops \(okta\)/ });
    expect(within(group).getByRole('combobox', { name: 'Role of orders-ops (okta)' })).toHaveValue('Team Viewer');
  });

  it('offers only team roles when choosing a role', async () => {
    serve(['user:admin']);
    const person = userEvent.setup();
    renderMembers();

    await person.click(await screen.findByRole('combobox', { name: /Team role/ }));
    expect(await screen.findAllByRole('option', { name: 'Team Viewer', hidden: true })).not.toHaveLength(0);
    expect(screen.getAllByRole('option', { name: 'Team Operator', hidden: true })).not.toHaveLength(0);
    expect(screen.queryByRole('option', { name: 'Operator', hidden: true })).not.toBeInTheDocument();
  });

  it('adds a user, who is not offered twice', async () => {
    serve(['user:admin']);
    let body: unknown;
    server.use(
      http.post('*/api/v1/teams/t-orders/members', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    const person = userEvent.setup();
    renderMembers();

    const picker = await screen.findByRole('combobox', { name: /^User/ });
    await person.click(picker);
    expect(await screen.findByText('Type at least two letters')).toBeInTheDocument();
    await person.type(picker, 'bo');
    await person.click(await screen.findByRole('option', { name: 'bob', hidden: true }));
    expect(screen.queryByRole('option', { name: 'alice', hidden: true })).not.toBeInTheDocument();
    await choose(person, screen, /Team role/, TEAM_VIEWER.name);
    await person.click(screen.getByRole('button', { name: 'Add member' }));

    await waitFor(() => expect(body).toEqual({ principalType: 'USER', userId: 'u-bob', roleId: TEAM_VIEWER.id }));
  });

  it('adds a directory group by provider and name when the caller is a user administrator', async () => {
    serve(['user:admin']);
    let body: unknown;
    server.use(
      http.post('*/api/v1/teams/t-orders/members', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    const person = userEvent.setup();
    renderMembers();

    await person.click(await screen.findByRole('radio', { name: 'Directory group' }));
    await choose(person, screen, /Identity provider/, 'Okta');
    await person.type(screen.getByRole('textbox', { name: /^Group/ }), 'billing-ops');
    await choose(person, screen, /Team role/, TEAM_OPERATOR.name);
    await person.click(screen.getByRole('button', { name: 'Add member' }));

    await waitFor(() =>
      expect(body).toEqual({
        principalType: 'GROUP',
        providerId: 'okta',
        groupName: 'billing-ops',
        roleId: TEAM_OPERATOR.id,
      }),
    );
  });

  it("changes a member's role in place", async () => {
    serve(['user:admin']);
    let put: { url: string; body: unknown } | undefined;
    server.use(
      http.put('*/api/v1/teams/t-orders/members/m1', async ({ request }) => {
        put = { url: new URL(request.url).pathname, body: await request.json() };
        return HttpResponse.json({});
      }),
    );
    const person = userEvent.setup();
    renderMembers();

    await choose(person, within(await screen.findByRole('row', { name: /alice/ })), 'Role of alice', 'Team Viewer');

    await waitFor(() =>
      expect(put).toEqual({ url: '/api/v1/teams/t-orders/members/m1', body: { roleId: TEAM_VIEWER.id } }),
    );
    expect(await screen.findByText(/Changed the role of alice/)).toBeInTheDocument();
  });

  it('removes a member only once their name is typed', async () => {
    serve(['user:admin']);
    let removed = '';
    server.use(
      http.delete('*/api/v1/teams/t-orders/members/:id', ({ params }) => {
        removed = String(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderMembers();

    await person.click(await screen.findByRole('button', { name: 'Remove alice' }));
    const dialog = await screen.findByRole('dialog', { name: 'Remove alice from Orders' });
    const confirm = within(dialog).getByRole('button', { name: 'Remove member' });
    await holdButton(confirm);

    await waitFor(() => expect(removed).toBe('m1'));
  });

  it('says why a member could not be added, in words', async () => {
    serve(['user:admin']);
    server.use(
      http.post('*/api/v1/teams/t-orders/members', () =>
        HttpResponse.json(
          { type: 'https://studio/problems/team-member-exists', title: 'Conflict', detail: 'exists' },
          { status: 409 },
        ),
      ),
    );
    const person = userEvent.setup();
    renderMembers();

    await choose(person, screen, /^User/, 'bob', 'bo');
    await choose(person, screen, /Team role/, TEAM_VIEWER.name);
    await person.click(screen.getByRole('button', { name: 'Add member' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('That user or group is already a member of this team.');
    expect(alert).toHaveTextContent('Change their role in the list instead.');
  });
});

describe('TeamMembers as a team admin', () => {
  it('keeps user members editable and gates the directory group with its reason', async () => {
    serve(['team:admin']);
    renderMembers();

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Role of orders-ops (okta)' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Remove orders-ops (okta)' })).toBeDisabled();
    expect(screen.getByRole('combobox', { name: 'Role of alice' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Remove alice' })).toBeEnabled();
    expect(screen.getByRole('radio', { name: 'Directory group' })).toBeDisabled();
    expect(screen.getAllByText(/because a group can admit users to Studio/).length).toBeGreaterThan(0);
  });

  it('lets a team admin find a user by name and add them, though they may not list every user', async () => {
    serve(['team:admin']);
    server.use(
      http.get('*/api/v1/users', () =>
        HttpResponse.json({ title: 'Forbidden', detail: 'Access denied.' }, { status: 403 }),
      ),
      http.get('*/api/v1/roles', () =>
        HttpResponse.json({ title: 'Forbidden', detail: 'Access denied.' }, { status: 403 }),
      ),
    );
    let body: unknown;
    server.use(
      http.post('*/api/v1/teams/t-orders/members', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    const person = userEvent.setup();
    renderMembers();

    await choose(person, screen, /^User/, 'bob', 'bo');
    await choose(person, screen, /Team role/, TEAM_VIEWER.name);
    await person.click(screen.getByRole('button', { name: 'Add member' }));

    await waitFor(() => expect(body).toEqual({ principalType: 'USER', userId: 'u-bob', roleId: TEAM_VIEWER.id }));
  });

  it('states that users cannot be looked up instead of showing an empty picker', async () => {
    serve(['team:admin']);
    server.use(
      http.get('*/api/v1/teams/lookups/users', () =>
        HttpResponse.json({ title: 'Forbidden', detail: 'Access denied.' }, { status: 403 }),
      ),
    );
    const person = userEvent.setup();
    renderMembers();

    await person.type(await screen.findByRole('combobox', { name: /^User/ }), 'al');
    expect(await screen.findByText(/Only an administrator of a team can look users up/)).toBeInTheDocument();
  });
});
