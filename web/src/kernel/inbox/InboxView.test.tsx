import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { InboxItem } from './api.ts';
import { InboxView } from './InboxView.tsx';

function notice(id: number, extra: Partial<InboxItem> = {}): InboxItem {
  return {
    id,
    source: 'studio',
    kind: 'notice',
    severity: 'info',
    title: `Notice ${id}`,
    body: `Body of ${id}`,
    link: null,
    data: null,
    createdAt: '2026-10-07T10:00:00Z',
    readAt: null,
    ...extra,
  };
}

function renderInbox(path = '/inbox') {
  const root = createRootRoute();
  const inbox = createRoute({
    getParentRoute: () => root,
    path: 'inbox',
    component: InboxView,
    validateSearch: (raw: Record<string, unknown>) => (raw.filter === 'unread' ? { filter: 'unread' } : {}),
  });
  const router = createRouter({
    routeTree: root.addChildren([inbox]),
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  return { ...renderWithProviders(<RouterProvider router={router as never} />), router };
}

/** Pages of the list by `before`; records what each request asked for. */
function mockList(pages: Record<string, { items: InboxItem[]; next: number | null }>) {
  const asked: URLSearchParams[] = [];
  server.use(
    http.get('*/api/v1/inbox', ({ request }) => {
      const params = new URL(request.url).searchParams;
      asked.push(params);
      return HttpResponse.json(pages[params.get('before') ?? 'first'] ?? { items: [], next: null });
    }),
  );
  return asked;
}

describe('InboxView', () => {
  it('lists the notices with their bodies under one heading', async () => {
    mockList({ first: { items: [notice(2), notice(1)], next: null } });
    renderInbox();
    expect(await screen.findByRole('heading', { level: 1, name: 'Inbox' })).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Notices' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByText('Body of 2')).toBeInTheDocument();
  });

  it('loads the next page by keyset', async () => {
    const asked = mockList({
      first: { items: [notice(9), notice(8)], next: 8 },
      8: { items: [notice(7)], next: null },
    });
    renderInbox();
    await userEvent.click(await screen.findByRole('button', { name: 'Load more' }));
    expect(await screen.findByText('Notice 7')).toBeInTheDocument();
    expect(asked.at(-1)?.get('before')).toBe('8');
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  });

  it('teaches what the inbox is when there is nothing in it', async () => {
    mockList({});
    renderInbox();
    expect(await screen.findByText('Your inbox is empty')).toBeInTheDocument();
  });

  it('says the unread filter excludes everything, and clears it', async () => {
    const asked = mockList({});
    const { router } = renderInbox('/inbox?filter=unread');
    expect(await screen.findByText('No unread notices')).toBeInTheDocument();
    expect(asked[0].get('unread')).toBe('true');
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(router.state.location.search).toEqual({}));
  });

  it('shows a failure as a failure, not as an empty inbox', async () => {
    server.use(
      http.get('*/api/v1/inbox', () => HttpResponse.json({ title: 'Boom', detail: 'Database down' }, { status: 500 })),
    );
    renderInbox();
    expect(await screen.findByRole('alert')).toHaveTextContent('Database down');
    expect(screen.queryByText('Your inbox is empty')).not.toBeInTheDocument();
  });

  it('dismisses a notice', async () => {
    mockList({ first: { items: [notice(3)], next: null } });
    const deleted: string[] = [];
    server.use(
      http.delete('*/api/v1/inbox/:id', ({ params }) => {
        deleted.push(String(params.id));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderInbox();
    await userEvent.click(await screen.findByRole('button', { name: 'Dismiss "Notice 3"' }));
    await waitFor(() => expect(deleted).toEqual(['3']));
  });

  it('marks every notice up to the newest read', async () => {
    mockList({ first: { items: [notice(12), notice(10)], next: null } });
    server.use(http.get('*/api/v1/inbox/count', () => HttpResponse.json({ unread: 2, capped: false })));
    const read: unknown[] = [];
    server.use(
      http.post('*/api/v1/inbox/read', async ({ request }) => {
        read.push(await request.json());
        return HttpResponse.json({ updated: 2 });
      }),
    );
    renderInbox();
    const markAll = await screen.findByRole('button', { name: 'Mark all read' });
    await waitFor(() => expect(markAll).toBeEnabled());
    await userEvent.click(markAll);
    await waitFor(() => expect(read).toEqual([{ upTo: 12 }]));
  });
});
