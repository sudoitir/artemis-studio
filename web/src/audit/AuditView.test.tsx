import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { server } from '../test/setup.ts';

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ clusterId: 'c1' }),
  useSearch: () => ({}),
  useNavigate: () => () => {},
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
});
