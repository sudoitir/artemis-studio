import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

let search: Record<string, unknown> = { q: 'orders' };
const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => navigate,
}));

// The drawer and the create form have their own tests; here they only show that the view opened them.
vi.mock('./QueueDetailDrawer.tsx', () => ({
  QueueDetailDrawer: ({ queue, onClose }: { queue: { queueName: string } | null; onClose: () => void }) =>
    queue ? (
      <div role="dialog" aria-label="Queue detail">
        {queue.queueName}
        <button onClick={onClose}>Close detail</button>
      </div>
    ) : null,
}));
vi.mock('./CreateQueueForm.tsx', () => ({
  CreateQueueForm: ({ opened }: { opened: boolean }) =>
    opened ? <div role="dialog" aria-label="New queue form" /> : null,
}));

beforeEach(() => {
  search = { q: 'orders' };
  navigate.mockReset();
});

const { QueuesView } = await import('./QueuesView.tsx');

function queue(name: string) {
  return {
    address: name,
    queueName: name,
    routingType: 'ANYCAST',
    durable: true,
    totalMessageCount: 1,
    totalConsumerCount: 0,
    totalDeliveringCount: 0,
    totalScheduledCount: 0,
    nodesPresent: 1,
    nodesTotal: 1,
    paused: false,
    perNode: [],
  };
}

describe('QueuesView selection', () => {
  it('offers to select every queue matching the filter, and says when it has', async () => {
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({
          id: 'u1',
          username: 'admin',
          mustChangePassword: false,
          grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
        }),
      ),
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({ id: 'c1', name: 'c1', topology: { nodes: [] }, capabilities: {} }),
      ),
      // 140 match, one page of two is shown.
      http.get('*/api/v1/clusters/c1/queues', () =>
        HttpResponse.json({ data: [queue('orders.a'), queue('orders.b')], count: 140, page: 1, pageSize: 200 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select all on this page' }));
    expect(screen.getByText('2 queues selected')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Select all 140 queues matching "orders"' }));
    expect(screen.getByText('All 140 queues matching "orders" are selected.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Clear selection' }));
    expect(screen.getByText(/No queues selected/)).toBeInTheDocument();
  });
});

const AVAILABLE = { status: 'AVAILABLE', reason: 'ok', brokerXmlSnippet: null };

function serve({
  queues = [],
  count,
  permissions = ['*'],
  nodes = [],
  managementWrite = AVAILABLE,
}: {
  queues?: ReturnType<typeof queue>[];
  count?: number;
  permissions?: string[];
  nodes?: object[];
  managementWrite?: object;
} = {}) {
  server.use(
    http.get('*/api/v1/auth/me', () =>
      HttpResponse.json({
        id: 'u1',
        username: 'admin',
        mustChangePassword: false,
        grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
      }),
    ),
    http.get('*/api/v1/clusters/c1', () =>
      HttpResponse.json({ id: 'c1', name: 'c1', topology: { nodes }, capabilities: { managementWrite } }),
    ),
    http.get('*/api/v1/clusters/c1/queues', () =>
      HttpResponse.json({ data: queues, count: count ?? queues.length, page: 1, pageSize: 200 }),
    ),
  );
}

/** What the last navigate() call does to the address it starts from. */
function nextSearch(prev: Record<string, unknown> = {}) {
  const call = navigate.mock.lastCall![0] as { search: (p: Record<string, unknown>) => Record<string, unknown> };
  return call.search(prev);
}

describe('QueuesView rows', () => {
  it('says in words when a queue is paused everywhere or only on some nodes, and stays quiet otherwise', async () => {
    search = {};
    serve({
      queues: [
        { ...queue('running'), durable: false },
        { ...queue('all'), paused: true, perNode: [{ paused: true }, { paused: true }] },
        { ...queue('some'), paused: true, perNode: [{ paused: true }, { paused: false }] },
      ] as never,
    });
    renderWithProviders(<QueuesView />);

    const grid = await screen.findByRole('grid', { name: 'Queues' });
    expect(await within(grid).findByText('paused')).toBeInTheDocument();
    expect(within(grid).getByText('paused on some nodes')).toBeInTheDocument();
    expect(within(grid).getByText('no')).toBeInTheDocument();
    expect(within(grid).getAllByText('yes')).toHaveLength(2);
    expect(screen.getByText('1–3 of 3 queues')).toBeInTheDocument();
  });

  it('puts the sort in the address and starts again at the first page', async () => {
    search = {};
    serve({ queues: [queue('a')] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await screen.findByRole('grid', { name: 'Queues' });
    await user.click(within(screen.getByRole('columnheader', { name: /Depth/ })).getByRole('button'));

    expect(nextSearch({ page: 3, q: 'x' })).toEqual({ page: undefined, q: 'x', sort: 'depth' });
  });

  it('pages through a long list and leaves the page out of the address on the first', async () => {
    search = { page: 2 };
    serve({ queues: [queue('a')], count: 450 });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('201–400 of 450 queues')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(nextSearch({ q: 'x' })).toEqual({ q: 'x', page: 3 });
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(nextSearch({ q: 'x' })).toEqual({ q: 'x', page: undefined });
  });

  it('writes the typed filter into the address once typing pauses, and resets the page', async () => {
    search = {};
    serve({ queues: [queue('a')] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.type(await screen.findByRole('textbox', { name: 'Filter queues' }), 'ord');

    await waitFor(() => expect(navigate).toHaveBeenCalled());
    expect(nextSearch({ page: 5 })).toEqual({ page: undefined, q: 'ord' });
  });

  it('states why the queues could not be listed instead of showing an empty grid', async () => {
    search = {};
    server.use(
      http.get('*/api/v1/clusters/c1/queues', () =>
        HttpResponse.json({ title: 'Listing failed', detail: 'No node answered.' }, { status: 502 }),
      ),
    );
    renderWithProviders(<QueuesView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Studio failed to complete the request');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    // The failure stands in place of the rows, not of the view: the filter is still there.
    expect(screen.getByRole('textbox', { name: 'Filter queues' })).toBeInTheDocument();
    expect(within(screen.getByRole('grid', { name: 'Queues' })).queryAllByRole('row')).toHaveLength(1);
  });
});

describe('QueuesView the open queue', () => {
  it('puts the clicked queue in the address, and opens the one the address names', async () => {
    search = {};
    serve({ queues: [{ ...queue('orders.a'), address: 'orders' }] });
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<QueuesView />);

    await user.click(await screen.findByText('orders.a', { selector: '[role="gridcell"]' }));
    expect(nextSearch({ q: 'x' })).toEqual({ q: 'x', queue: 'orders.a' });
    unmount();

    search = { queue: 'orders.a' };
    renderWithProviders(<QueuesView />);
    const dialog = await screen.findByRole('dialog', { name: 'Queue detail' });
    expect(dialog).toHaveTextContent('orders.a');
    await user.click(within(dialog).getByRole('button', { name: 'Close detail' }));
    expect(nextSearch({ queue: 'orders.a' })).toEqual({ queue: undefined });
  });

  it('looks up a queue named by the address that is not on the loaded page', async () => {
    search = { queue: 'elsewhere' };
    server.use(
      http.get('*/api/v1/auth/me', () =>
        HttpResponse.json({ id: 'u1', username: 'admin', mustChangePassword: false, grants: [] }),
      ),
      http.get('*/api/v1/clusters/c1', () =>
        HttpResponse.json({ id: 'c1', name: 'c1', topology: { nodes: [] }, capabilities: {} }),
      ),
      http.get('*/api/v1/clusters/c1/queues', ({ request }) => {
        const asked = new URL(request.url).searchParams.get('q');
        return HttpResponse.json({
          data: asked === 'elsewhere' ? [queue('elsewhere')] : [queue('orders.a')],
          count: 1,
          page: 1,
          pageSize: 200,
        });
      }),
    );
    renderWithProviders(<QueuesView />);

    expect(await screen.findByRole('dialog', { name: 'Queue detail' })).toHaveTextContent('elsewhere');
  });
});

describe('QueuesView selection', () => {
  it('picks and unpicks rows, and the page checkbox picks and unpicks them all', async () => {
    search = {};
    serve({ queues: [queue('orders.a'), queue('orders.b')] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select row orders.a::orders.a::ANYCAST' }));
    expect(screen.getByText('1 queue selected')).toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Select row orders.a::orders.a::ANYCAST' }));
    expect(screen.getByText(/No queues selected/)).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Select all on this page' }));
    expect(screen.getByText('2 queues selected')).toBeInTheDocument();
    // Every queue is on this page, so there is nothing more to offer to select.
    expect(screen.queryByRole('button', { name: /Select all 2 queues/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole('checkbox', { name: 'Deselect all on this page' }));
    expect(screen.getByText(/No queues selected/)).toBeInTheDocument();
  });

  it('turns "all matching" back into the names on this page when one row is changed', async () => {
    search = {};
    serve({ queues: [queue('orders.a'), queue('orders.b')], count: 140 });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select all on this page' }));
    await user.click(screen.getByRole('button', { name: 'Select all 140 queues on this cluster' }));
    expect(screen.getByText('All 140 queues on this cluster are selected.')).toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: 'Select row orders.a::orders.a::ANYCAST' }));
    expect(screen.getByText('1 queue selected')).toBeInTheDocument();

    // One of the two was dropped, so the whole page is no longer picked and there is nothing to widen.
    expect(screen.queryByRole('button', { name: /Select all 140 queues/ })).not.toBeInTheDocument();
  });

  it('forgets the selection when the filter changes, since it was made under another set of queues', async () => {
    search = {};
    serve({ queues: [queue('orders.a')] });
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('checkbox', { name: 'Select row orders.a::orders.a::ANYCAST' }));
    expect(screen.getByText('1 queue selected')).toBeInTheDocument();

    search = { q: 'orders' };
    rerender(<QueuesView />);
    await waitFor(() => expect(screen.getByText(/No queues selected/)).toBeInTheDocument());
  });
});

describe('QueuesView empty grid', () => {
  it('says the filter emptied it, and clears the filter from the address', async () => {
    search = { q: 'zzz' };
    serve();
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('No queue matches "zzz"')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(nextSearch({ q: 'zzz', page: 2 })).toEqual({ q: undefined, page: undefined });
    expect(screen.getByRole('textbox', { name: 'Filter queues' })).toHaveValue('');
  });

  it('says one unreachable node makes this an incomplete view rather than an empty cluster', async () => {
    search = {};
    serve({ nodes: [{ endpoints: [{ name: 'node-a', lastError: 'connection refused' }, { name: 'node-b' }] }] });
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('node-a could not be reached')).toBeInTheDocument();
    expect(screen.getByText(/This node did not answer the last scrape/)).toBeInTheDocument();
    expect(screen.queryByText('No queues yet')).not.toBeInTheDocument();
  });

  it('names how many nodes are unreachable, and which', async () => {
    search = {};
    serve({
      nodes: [
        { endpoints: [{ name: 'node-a', lastError: 'refused' }] },
        { endpoints: [{ name: 'node-b', lastError: 'timeout' }] },
      ],
    });
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('2 nodes could not be reached')).toBeInTheDocument();
    expect(screen.getByText(/These nodes did not answer/)).toBeInTheDocument();
    const nodes = screen.getByRole('list', { name: 'Nodes that could not be reached' });
    expect(
      within(nodes)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['node-a', 'node-b']);
  });

  it('teaches what a queue is when there is none, and offers to create the first where allowed', async () => {
    search = {};
    serve();
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('No queues yet')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Create the first queue' }));
    expect(await screen.findByRole('dialog', { name: 'New queue form' })).toBeInTheDocument();
  });

  it('does not offer to create the first queue to someone who may not', async () => {
    search = {};
    serve({ permissions: ['queue:read'] });
    renderWithProviders(<QueuesView />);

    expect(await screen.findByText('No queues yet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create the first queue' })).not.toBeInTheDocument();
  });
});

describe('QueuesView creating a queue', () => {
  it('opens the create form from New queue', async () => {
    search = {};
    serve({ queues: [queue('a')] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('button', { name: 'New queue' }));

    expect(await screen.findByRole('dialog', { name: 'New queue form' })).toBeInTheDocument();
  });

  it('keeps New queue visible but disabled, with the permission it lacks, for a read-only operator', async () => {
    search = {};
    serve({ queues: [queue('a')], permissions: ['queue:read'] });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    // While grants load the control is offered; it settles to disabled once they say so.
    await waitFor(() => expect(screen.getByRole('button', { name: 'New queue' })).toBeDisabled());
    await user.click(await screen.findByRole('button', { name: 'Why creating a queue is unavailable' }));
    expect(await screen.findByText(/You do not have the "Create queues and addresses" permission/)).toBeInTheDocument();
  });

  it('says why when the broker connection cannot write', async () => {
    search = {};
    serve({
      queues: [queue('a')],
      managementWrite: {
        status: 'UNAVAILABLE',
        reason: 'Management is read-only on this broker.',
        brokerXmlSnippet: null,
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<QueuesView />);

    await user.click(await screen.findByRole('button', { name: 'Why creating a queue is unavailable' }));
    expect(await screen.findByText('Management is read-only on this broker.')).toBeInTheDocument();
  });
});
