import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';

let search: Record<string, unknown> = {};
const navigate = vi.fn();
vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => search,
  useNavigate: () => navigate,
}));

const { AuditView } = await import('./AuditView.tsx');

function row(over: Record<string, unknown> = {}) {
  return {
    ts: '2026-09-04T10:00:00.000Z',
    username: 'anonymous',
    sourceIp: '10.0.0.1',
    requestId: 'req-1',
    action: 'DELETE_MESSAGES',
    targetType: 'QUEUE',
    targetName: 'ORDERS',
    affectedCount: null,
    outcome: 'FAILURE',
    dryRun: false,
    params: '{"filter":"x"}',
    error: 'broker refused',
    nodeId: null,
    ...over,
  };
}

describe('AuditView', () => {
  it('renders the outcome word for a failed row and expands to show params/error', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row()], count: 1, page: 1, pageSize: 100 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    expect(await screen.findByText('failure')).toBeInTheDocument();
    await user.click(screen.getAllByText('DELETE_MESSAGES')[0]);
    expect(await screen.findByText('broker refused')).toBeInTheDocument();
    expect(screen.getByText(/req-1/)).toBeInTheDocument();
  });

  it('filters to the events of one run, says so, and offers the way back', async () => {
    search = { parentId: 7 };
    let asked: string | null = null;
    server.use(
      http.get('*/api/v1/clusters/c1/audit', ({ request }) => {
        asked = new URL(request.url).searchParams.get('parentId');
        return HttpResponse.json({ data: [row({ id: 8, parentId: 7 })], count: 1, page: 1, pageSize: 100 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    expect(await screen.findByText(/Showing the events that belong to audit event 7/)).toBeInTheDocument();
    expect(asked).toBe('7');
    await user.click(screen.getByRole('button', { name: 'Show every event' }));
    expect(navigate).toHaveBeenCalled();
    search = {};
  });

  it('links a child event to the run it belongs to', async () => {
    server.use(
      http.get('*/api/v1/clusters/c1/audit', () =>
        HttpResponse.json({ data: [row({ id: 8, parentId: 7 })], count: 1, page: 1, pageSize: 100 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuditView />);

    expect(await screen.findByText('failure')).toBeInTheDocument();
    await user.click(within(screen.getByRole('grid')).getByText('DELETE_MESSAGES'));
    await user.click(await screen.findByRole('button', { name: 'Show the operation this belongs to, with all its parts' }));
    const call = navigate.mock.calls.at(-1)![0] as { search: (p: object) => Record<string, unknown> };
    expect(call.search({})).toMatchObject({ parentId: 7 });
  });
});
