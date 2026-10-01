import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';

import { server } from '../../test/setup.ts';
import { renderWithProviders } from '../../test/render.tsx';
import type { AccountSessionView } from './api.ts';
import { SessionsManager } from './SessionsManager.tsx';
import { paged } from '../../kernel/api/paging.ts';

const FIREFOX = 'Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0';
const CHROME =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36';

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

const session = (handle: string, over: Partial<AccountSessionView> = {}): AccountSessionView => ({
  handle,
  signedInAt: ago(180),
  lastActivityAt: ago(5),
  clientAddress: '203.0.113.7',
  userAgent: FIREFOX,
  current: false,
  ...over,
});

const HERE = session('a'.repeat(32), { current: true });
const PHONE = session('b'.repeat(32), { userAgent: CHROME, clientAddress: '198.51.100.9', lastActivityAt: ago(90) });
const TABLET = session('c'.repeat(32), { userAgent: 'curl/8.5.0', clientAddress: '198.51.100.10' });

function serve(state: { sessions: AccountSessionView[] }, path = '*/api/v1/auth/sessions') {
  server.use(http.get(path, () => HttpResponse.json(paged(state.sessions))));
}

describe('SessionsManager, own sessions', () => {
  const originalLocation = window.location;

  beforeEach(() => {
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, pathname: '/account', assign: vi.fn() },
    });
  });
  afterEach(() => {
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  it('lists each session with its client, address and times, and marks the current one in words', async () => {
    serve({ sessions: [HERE, PHONE] });

    renderWithProviders(<SessionsManager />);

    const rows = await screen.findAllByRole('listitem');
    const here = rows.find((r) => within(r).queryByText('This session'))!;
    expect(within(here).getByText('Firefox on Linux')).toBeInTheDocument();
    expect(here).toHaveTextContent('203.0.113.7 · signed in 3h ago');
    expect(within(here).getByText('5m ago')).toBeInTheDocument();
    expect(within(here).getByText('3h ago')).toBeInTheDocument();
    // The exact time is text for assistive technology, not only behind a hover.
    expect(within(here).getAllByText(/^\(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/)).toHaveLength(2);
    const phone = rows.find((r) => within(r).queryByText('Chrome on Windows'))!;
    expect(within(phone).queryByText('This session')).not.toBeInTheDocument();
    expect(within(phone).getByText('1h ago')).toBeInTheDocument();
  });

  it('signs out another session, announcing progress and the outcome, and drops it from the list', async () => {
    const state = { sessions: [HERE, PHONE] };
    serve(state);
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    server.use(
      http.delete(`*/api/v1/auth/sessions/${PHONE.handle}`, async () => {
        await gate;
        state.sessions = [HERE];
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderWithProviders(<SessionsManager />);

    await person.click(await screen.findByRole('button', { name: 'Sign out Chrome on Windows at 198.51.100.9' }));

    expect(await screen.findByText('Signing out of the session…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out of this session' })).toBeDisabled();
    release();

    const outcome = await screen.findByText('Signed out Chrome on Windows at 198.51.100.9.');
    expect(outcome.closest('[aria-live="polite"]')).not.toBeNull();
    await waitFor(() => expect(screen.queryByText('Chrome on Windows')).not.toBeInTheDocument());
  });

  it('says why a sign-out failed and what to do, and keeps the session listed', async () => {
    serve({ sessions: [HERE, PHONE] });
    server.use(
      http.delete(`*/api/v1/auth/sessions/${PHONE.handle}`, () =>
        HttpResponse.json({ title: 'Error', detail: 'The session store is unavailable.' }, { status: 500 }),
      ),
    );
    const person = userEvent.setup();
    renderWithProviders(<SessionsManager />);

    await person.click(await screen.findByRole('button', { name: /^Sign out Chrome on Windows/ }));

    const outcome = await screen.findByText(
      'Could not end Chrome on Windows at 198.51.100.9. The session store is unavailable. Try again.',
    );
    expect(outcome.closest('[aria-live="polite"]')).not.toBeNull();
    expect(screen.getByRole('button', { name: /^Sign out Chrome on Windows/ })).toBeEnabled();
  });

  it('does not call it a failure when the session had already ended', async () => {
    serve({ sessions: [HERE, PHONE] });
    server.use(
      http.delete(`*/api/v1/auth/sessions/${PHONE.handle}`, () =>
        HttpResponse.json({ title: 'Not found', detail: 'session x does not exist.' }, { status: 404 }),
      ),
    );
    const person = userEvent.setup();
    renderWithProviders(<SessionsManager />);

    await person.click(await screen.findByRole('button', { name: /^Sign out Chrome on Windows/ }));

    expect(await screen.findByText('Chrome on Windows at 198.51.100.9 had already ended.')).toBeInTheDocument();
  });

  it('signs out every other session at once and says how many', async () => {
    const state = { sessions: [HERE, PHONE, TABLET] };
    serve(state);
    server.use(
      http.delete('*/api/v1/auth/sessions', () => {
        state.sessions = [HERE];
        return HttpResponse.json({ ended: 2 });
      }),
    );
    const person = userEvent.setup();
    renderWithProviders(<SessionsManager />);

    await person.click(await screen.findByRole('button', { name: 'Sign out all other sessions (2)' }));

    expect(await screen.findByText('Signed out 2 other sessions.')).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText('Chrome on Windows')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Sign out all other sessions' })).toBeDisabled();
  });

  it('keeps the bulk action visible and says why it is unavailable when this is the only session', async () => {
    serve({ sessions: [HERE] });

    renderWithProviders(<SessionsManager />);

    expect(await screen.findByRole('button', { name: 'Sign out all other sessions' })).toBeDisabled();
    expect(screen.getByText('You are not signed in anywhere else.')).toBeInTheDocument();
  });

  it('signs out of the current session through sign-out and returns to the sign-in screen', async () => {
    serve({ sessions: [HERE, PHONE] });
    let loggedOut = false;
    server.use(
      http.post('*/api/v1/auth/logout', () => {
        loggedOut = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const person = userEvent.setup();
    renderWithProviders(<SessionsManager />);

    await person.click(await screen.findByRole('button', { name: 'Sign out of this session' }));

    await waitFor(() => expect(window.location.assign).toHaveBeenCalledWith('/login'));
    expect(loggedOut).toBe(true);
  });

  it('shows loading, then says it could not load and offers a retry', async () => {
    let calls = 0;
    server.use(
      http.get('*/api/v1/auth/sessions', () => {
        calls += 1;
        return calls === 1
          ? HttpResponse.json({ title: 'Error', detail: 'The database is down.' }, { status: 500 })
          : HttpResponse.json(paged([HERE]));
      }),
    );
    const person = userEvent.setup();
    renderWithProviders(<SessionsManager />);

    expect(screen.getByRole('status')).toHaveTextContent('Loading sessions');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Studio failed to complete the request');
    expect(alert).toHaveTextContent('The database is down.');
    await person.click(within(alert).getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('This session')).toBeInTheDocument();
  });
});
