import { afterEach, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { notifications } from '@mantine/notifications';

import { paged } from '../../kernel/api/paging.ts';
import { renderAppAt } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { preview, serveLookups, summary, team } from '../../test/teams.ts';

afterEach(() => act(() => notifications.clean()));

const orders = team();

function serveTeams(permissions = ['user:admin']) {
  serveLookups(permissions);
  server.use(
    http.get('*/api/v1/teams', () => HttpResponse.json(paged([summary(orders)]))),
    http.get('*/api/v1/teams/t-orders', () => HttpResponse.json(orders)),
    http.get('*/api/v1/users', () => HttpResponse.json(paged([]))),
    http.get('*/api/v1/auth/providers', () => HttpResponse.json(paged([]))),
  );
}

describe('the Teams tab', () => {
  it('lists each team with its counts, and opens one into the address', async () => {
    serveTeams();
    const person = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=teams');

    const row = await screen.findByRole('row', { name: /Orders/ });
    expect(row).toHaveTextContent('Orders');
    expect(screen.getByText('1 team')).toBeInTheDocument();

    await person.click(within(row).getByRole('button', { name: 'Orders' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Orders' })).toBeInTheDocument();
    expect(router.state.location.search).toMatchObject({ tab: 'teams', team: 't-orders' });
    expect(screen.getByRole('tab', { name: 'Patterns' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('opens the team and section the address names, and goes back to the list', async () => {
    serveTeams();
    const person = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=teams&team=t-orders&teamTab=members');

    expect(await screen.findByRole('tab', { name: 'Members' })).toHaveAttribute('aria-selected', 'true');
    await person.click(screen.getByRole('button', { name: 'All teams' }));

    expect(await screen.findByRole('table', { name: 'Teams' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ tab: 'teams' });
  });

  it('teaches what a team is when there are none', async () => {
    serveLookups();
    server.use(http.get('*/api/v1/teams', () => HttpResponse.json(paged([]))));
    renderAppAt('/admin?tab=teams');

    expect(await screen.findByText('No teams')).toBeInTheDocument();
    expect(screen.getByText(/Create one to share a cluster between groups of people/)).toBeInTheDocument();
  });

  it('states a failed load and offers a retry, rather than showing no teams', async () => {
    serveLookups();
    server.use(
      http.get('*/api/v1/teams', () =>
        HttpResponse.json({ title: 'Boom', detail: 'The database is down.' }, { status: 500 }),
      ),
    );
    renderAppAt('/admin?tab=teams');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The database is down.');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('creates a team, and answers a name already taken beside the field', async () => {
    serveTeams();
    let attempts = 0;
    server.use(
      http.post('*/api/v1/teams', () => {
        attempts += 1;
        return HttpResponse.json(
          { type: 'https://studio/problems/duplicate-team-name', title: 'Conflict', detail: 'exists' },
          { status: 409 },
        );
      }),
    );
    const person = userEvent.setup();
    renderAppAt('/admin?tab=teams');

    await person.click(await screen.findByRole('button', { name: 'New team' }));
    const dialog = await screen.findByRole('dialog', { name: 'New team' });
    await person.click(within(dialog).getByRole('button', { name: 'Create team' }));
    expect(await within(dialog).findByText(/Name the team after/)).toBeInTheDocument();
    expect(attempts).toBe(0);

    await person.type(within(dialog).getByRole('textbox', { name: /Name/ }), 'Orders');
    await person.click(within(dialog).getByRole('button', { name: 'Create team' }));

    expect(
      await within(dialog).findByText('A team with that name already exists. Choose another name.'),
    ).toBeInTheDocument();
    expect(within(dialog).getByRole('textbox', { name: /Name/ })).toHaveFocus();
  });

  it('deletes a team only once its name is typed, stating what goes with it', async () => {
    serveTeams();
    let deleted = '';
    server.use(
      http.delete('*/api/v1/teams/:id', ({ params }) => {
        deleted = String(params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderAppAt('/admin?tab=teams');

    await person.click(await screen.findByRole('button', { name: 'Delete Orders' }));
    const dialog = await screen.findByRole('dialog', { name: 'Delete Orders' });
    expect(dialog).toHaveTextContent("team's 1 pattern, 2 members and 0 shares");
    const confirm = within(dialog).getByRole('button', { name: 'Delete team' });
    expect(confirm).toBeDisabled();
    await person.type(within(dialog).getByLabelText('Type "Orders" to confirm'), 'Orders');
    await person.click(confirm);

    await waitFor(() => expect(deleted).toBe('t-orders'));
  });

  it('shows a team admin their teams, and keeps creating, renaming and deleting for administrators', async () => {
    serveTeams(['team:admin']);
    renderAppAt('/admin?tab=teams');

    expect(await screen.findByRole('row', { name: /Orders/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'New team' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Rename Orders' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete Orders' })).toBeDisabled();
    expect(screen.getByText(/You see the teams you administer/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Why creating a team is unavailable' })).toBeInTheDocument();
  });
});

describe('the Unowned section', () => {
  it('lists what no team owns on a cluster and pre-fills a pattern from one name', async () => {
    serveTeams();
    let asked = '';
    server.use(
      http.get('*/api/v1/clusters/c-prod/unowned', ({ request }) => {
        asked = new URL(request.url).search;
        return HttpResponse.json(paged([{ name: 'legacy.inbox' }, { name: 'tmp.scratch' }]));
      }),
      http.get('*/api/v1/teams/t-orders/patterns/preview', ({ request }) =>
        HttpResponse.json(preview({ pattern: new URL(request.url).searchParams.get('pattern') ?? '' })),
      ),
    );
    const person = userEvent.setup();
    renderAppAt('/admin?tab=teams&team=t-orders&teamTab=unowned');

    await person.click(await screen.findByRole('combobox', { name: 'Cluster' }));
    await person.click(await screen.findByRole('option', { name: 'prod', hidden: true }));

    expect(await screen.findByRole('row', { name: /legacy\.inbox/ })).toBeInTheDocument();
    expect(asked).toContain('kind=QUEUE');

    await person.click(screen.getByRole('button', { name: 'Assign legacy.inbox to this team' }));

    expect(await screen.findByRole('tab', { name: 'Patterns' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByRole('textbox', { name: /^Pattern/ })).toHaveValue('legacy.inbox');
  });

  it('says every name is owned when none is left', async () => {
    serveTeams();
    server.use(http.get('*/api/v1/clusters/c-prod/unowned', () => HttpResponse.json(paged([]))));
    const person = userEvent.setup();
    renderAppAt('/admin?tab=teams&team=t-orders&teamTab=unowned');

    await person.click(await screen.findByRole('combobox', { name: 'Cluster' }));
    await person.click(await screen.findByRole('option', { name: 'prod', hidden: true }));

    expect(await screen.findByText('Every queue on prod is owned')).toBeInTheDocument();
  });

  it('is withheld from a team admin with the reason, and asks the server nothing', async () => {
    serveTeams(['team:admin']);
    renderAppAt('/admin?tab=teams&team=t-orders&teamTab=unowned');

    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Cluster' })).toBeDisabled());
    expect(
      screen.getByText(/Listing what no team owns, and assigning it, needs the user:admin permission/),
    ).toBeInTheDocument();
  });
});
