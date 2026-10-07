import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router';

import { renderWithProviders } from '../../test/render.tsx';
import { EventSourceStub, server } from '../../test/setup.ts';
import type { InboxItem } from './api.ts';
import { InboxBell } from './InboxBell.tsx';

function notice(id: number, extra: Partial<InboxItem> = {}): InboxItem {
  return {
    id,
    source: 'studio',
    kind: 'approval.requested',
    severity: 'info',
    title: `Notice ${id}`,
    body: null,
    link: null,
    data: null,
    createdAt: '2026-10-07T10:00:00Z',
    readAt: null,
    ...extra,
  };
}

/** The bell in a header over a real router, with an inbox page and a request page to arrive at. */
function renderBell() {
  const root = createRootRoute({
    component: () => (
      <>
        <InboxBell />
        <Outlet />
      </>
    ),
  });
  const page = (path: string, text: string) =>
    createRoute({ getParentRoute: () => root, path, component: () => <p>{text}</p> });
  const router = createRouter({
    routeTree: root.addChildren([
      page('/', 'Home'),
      page('inbox', 'Inbox page'),
      page('approvals/$id', 'Request page'),
    ]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  return { ...renderWithProviders(<RouterProvider router={router as never} />), router };
}

function mockInbox(count: { unread: number; capped: boolean }, items: InboxItem[] = []) {
  const read: unknown[] = [];
  server.use(
    http.get('*/api/v1/inbox/count', () => HttpResponse.json(count)),
    http.get('*/api/v1/inbox', () => HttpResponse.json({ items, next: null })),
    http.post('*/api/v1/inbox/read', async ({ request }) => {
      read.push(await request.json());
      return HttpResponse.json({ updated: 1 });
    }),
  );
  return read;
}

describe('InboxBell', () => {
  it('names the unread count in the bell, as the badge shows it', async () => {
    mockInbox({ unread: 3, capped: false });
    renderBell();
    expect(await screen.findByRole('button', { name: 'Notifications, 3 unread' })).toBeInTheDocument();
  });

  it('shows 99+ once the server stops counting', async () => {
    mockInbox({ unread: 100, capped: true });
    renderBell();
    expect(await screen.findByRole('button', { name: 'Notifications, 99+ unread' })).toBeInTheDocument();
  });

  it('says none are unread rather than showing a zero', async () => {
    mockInbox({ unread: 0, capped: false });
    renderBell();
    expect(await screen.findByRole('button', { name: 'Notifications, none unread' })).toBeInTheDocument();
  });

  it('lists the latest notices in its popover, with the way to the inbox', async () => {
    mockInbox({ unread: 1, capped: false }, [notice(2), notice(1, { readAt: '2026-10-07T11:00:00Z' })]);
    renderBell();
    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }));
    const list = await screen.findByRole('list', { name: 'Latest notifications' });
    const rows = within(list).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Unread, Notice 2');
    expect(rows[1]).not.toHaveTextContent('Unread');
    expect(screen.getByRole('link', { name: 'Open inbox' })).toHaveAttribute('href', '/inbox');
  });

  it('teaches what the inbox is when it is empty', async () => {
    mockInbox({ unread: 0, capped: false }, []);
    renderBell();
    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, none unread' }));
    expect(await screen.findByText('No notifications')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark all read' })).toBeDisabled();
  });

  it('marks everything up to the newest notice read', async () => {
    const read = mockInbox({ unread: 2, capped: false }, [notice(7), notice(5)]);
    renderBell();
    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }));
    await screen.findByRole('list', { name: 'Latest notifications' });
    await userEvent.click(screen.getByRole('button', { name: 'Mark all read' }));
    await waitFor(() => expect(read).toEqual([{ upTo: 7 }]));
  });

  it('opening a notice marks it read and follows its link inside the app', async () => {
    const read = mockInbox({ unread: 1, capped: false }, [notice(4, { link: '/approvals/h-1' })]);
    const { router } = renderBell();
    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }));
    await userEvent.click(await screen.findByRole('link', { name: 'Unread, Notice 4' }));
    expect(await screen.findByText('Request page')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/approvals/h-1');
    await waitFor(() => expect(read).toEqual([{ ids: [4] }]));
  });

  it('never links a notice outside Studio', async () => {
    mockInbox({ unread: 1, capped: false }, [notice(4, { link: '//evil.example/x' })]);
    renderBell();
    await userEvent.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }));
    await screen.findByRole('list', { name: 'Latest notifications' });
    expect(screen.queryByRole('link', { name: /Notice 4/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Unread, Notice 4' })).toBeInTheDocument();
  });

  it('updates the count live on an inbox signal and announces the new notice politely', async () => {
    let unread = 1;
    server.use(http.get('*/api/v1/inbox/count', () => HttpResponse.json({ unread, capped: false })));
    renderBell();
    await screen.findByRole('button', { name: 'Notifications, 1 unread' });
    unread = 2;
    act(() => EventSourceStub.emit('inbox', ''));
    expect(await screen.findByRole('button', { name: 'Notifications, 2 unread' })).toBeInTheDocument();
    expect(screen.getByText('A new notice. 2 unread.')).toBeInTheDocument();
  });
});
