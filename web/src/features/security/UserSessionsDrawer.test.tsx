import { describe, expect, it } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../test/setup.ts';
import { renderWithProviders } from '../../test/render.tsx';
import type { AccountSessionView, UserView } from './api.ts';
import { UsersPanel } from './UsersPanel.tsx';

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

const alice: UserView = {
  id: 'id-alice',
  username: 'alice',
  email: null,
  providerId: 'local',
  disabled: false,
  mustChangePassword: false,
  lockedUntil: null,
  secondFactors: [],
  secondFactorRequired: false,
  grants: [],
};

const session = (handle: string, address: string): AccountSessionView => ({
  handle,
  signedInAt: ago(60),
  lastActivityAt: ago(2),
  clientAddress: address,
  userAgent: 'Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0',
  current: false,
});

const LAPTOP = session('a'.repeat(32), '203.0.113.7');
const PHONE = session('b'.repeat(32), '198.51.100.9');

function serve(state: { sessions: AccountSessionView[] }) {
  server.use(
    http.get('*/api/v1/users', () => HttpResponse.json([alice])),
    http.get('*/api/v1/roles', () => HttpResponse.json([])),
    http.get('*/api/v1/users/id-alice/sessions', () => HttpResponse.json(state.sessions)),
  );
}

describe('the sessions drawer of a user', () => {
  it('opens from the row, lists the sessions, ends one and announces it', async () => {
    const state = { sessions: [LAPTOP, PHONE] };
    serve(state);
    server.use(
      http.delete(`*/api/v1/users/id-alice/sessions/${PHONE.handle}`, () => {
        state.sessions = [LAPTOP];
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);

    await person.click(await screen.findByRole('button', { name: 'Sessions of alice' }));

    const drawer = await screen.findByRole('dialog', { name: 'Sessions of alice' });
    expect(await within(drawer).findAllByText('Firefox on Linux')).toHaveLength(2);
    await person.click(within(drawer).getByRole('button', { name: 'End Firefox on Linux at 198.51.100.9' }));

    const outcome = await within(drawer).findByText('Ended Firefox on Linux at 198.51.100.9.');
    expect(outcome.closest('[aria-live="polite"]')).not.toBeNull();
    await waitFor(() => expect(within(drawer).queryByText('198.51.100.9')).not.toBeInTheDocument());
  });

  it("ends all of a user's sessions with one action and says how many", async () => {
    const state = { sessions: [LAPTOP, PHONE] };
    serve(state);
    server.use(
      http.delete('*/api/v1/users/id-alice/sessions', () => {
        state.sessions = [];
        return HttpResponse.json({ ended: 2 });
      }),
    );
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);
    await person.click(await screen.findByRole('button', { name: 'Sessions of alice' }));
    const drawer = await screen.findByRole('dialog', { name: 'Sessions of alice' });

    await person.click(await within(drawer).findByRole('button', { name: 'End all sessions (2)' }));

    expect(await within(drawer).findByText('Ended 2 sessions.')).toBeInTheDocument();
    expect(await within(drawer).findByText(/This user is not signed in anywhere/)).toBeInTheDocument();
  });

  it('says what to do when the administrator may not end sessions', async () => {
    serve({ sessions: [LAPTOP] });
    server.use(
      http.delete(`*/api/v1/users/id-alice/sessions/${LAPTOP.handle}`, () =>
        HttpResponse.json({ title: 'Forbidden', detail: 'Access denied.' }, { status: 403 }),
      ),
    );
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);
    await person.click(await screen.findByRole('button', { name: 'Sessions of alice' }));
    const drawer = await screen.findByRole('dialog', { name: 'Sessions of alice' });

    await person.click(await within(drawer).findByRole('button', { name: /^End Firefox on Linux/ }));

    expect(
      await within(drawer).findByText(
        'Could not end Firefox on Linux at 203.0.113.7. You need the user:admin permission for this. Ask an administrator.',
      ),
    ).toBeInTheDocument();
  });

  it('works from the keyboard: opens from the row, focus enters, Escape closes, focus returns', async () => {
    serve({ sessions: [LAPTOP] });
    const person = userEvent.setup();
    renderWithProviders(<UsersPanel />);

    const trigger = await screen.findByRole('button', { name: 'Sessions of alice' });
    trigger.focus();
    await person.keyboard('{Enter}');

    const drawer = await screen.findByRole('dialog', { name: 'Sessions of alice' });
    await waitFor(() => expect(drawer).toContainElement(document.activeElement as HTMLElement));
    const end = await within(drawer).findByRole('button', { name: /^End Firefox on Linux/ });
    await person.tab();
    await waitFor(() => expect(drawer).toContainElement(document.activeElement as HTMLElement));
    expect(end).toBeInTheDocument();

    await person.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Sessions of alice' })).toBeNull());
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
