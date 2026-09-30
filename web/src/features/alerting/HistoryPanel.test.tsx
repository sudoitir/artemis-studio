import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { AlertFiringView } from './api.ts';
import { HistoryPanel } from './HistoryPanel.tsx';
import type { PagedView } from '../../kernel/api/paging.ts';

const firing = (seq: number, over: Partial<AlertFiringView> = {}): AlertFiringView => ({
  seq,
  ruleId: 'r-1',
  clusterId: 'c-1',
  ruleName: `Backlog ${seq}`,
  subjectKey: `orders-${seq}`,
  severity: 'CRITICAL',
  startedAt: '2026-09-11T10:00:00Z',
  resolvedAt: null,
  ...over,
});

/** Serves the history as the pages the server would: `size` is ignored, each page is the fixture given for it. */
function serve(pages: Record<string, PagedView<AlertFiringView>>) {
  const requested: string[] = [];
  server.use(
    http.get('*/api/v1/clusters/c-1/alerts/history', ({ request }) => {
      const page = new URL(request.url).searchParams.get('page') ?? '1';
      requested.push(page);
      return HttpResponse.json(pages[page]);
    }),
  );
  return requested;
}

describe('HistoryPanel', () => {
  it('teaches what an empty history means', async () => {
    serve({ '1': { data: [], page: 1, pageSize: 50, count: 0, hasNext: false } });
    renderWithProviders(<HistoryPanel clusterId="c-1" />);

    expect(await screen.findByText('No firings recorded yet for this cluster.')).toBeInTheDocument();
  });

  it('lists the firings with the total, and says which are still firing', async () => {
    serve({
      '1': {
        data: [firing(1), firing(2, { severity: 'WARNING', resolvedAt: '2026-09-11T11:00:00Z' })],
        page: 1,
        pageSize: 50,
        count: 2,
        hasNext: false,
      },
    });
    renderWithProviders(<HistoryPanel clusterId="c-1" />);

    const row = (await screen.findByText('orders-1')).closest('tr')!;
    expect(within(row).getByText('critical')).toBeInTheDocument();
    expect(within(row).getByText('still firing')).toBeInTheDocument();
    expect(screen.getByText('2 total')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  it('pages on the envelope: Next only while the server has a next page', async () => {
    const requested = serve({
      '1': { data: [firing(1)], page: 1, pageSize: 50, count: 51, hasNext: true },
      '2': { data: [firing(51)], page: 2, pageSize: 50, count: 51, hasNext: false },
    });
    renderWithProviders(<HistoryPanel clusterId="c-1" />);
    const user = userEvent.setup();

    await screen.findByText('orders-1');
    const next = screen.getByRole('button', { name: 'Next' });
    expect(next).toBeEnabled();
    await user.click(next);

    expect(await screen.findByText('orders-51')).toBeInTheDocument();
    expect(requested).toContain('2');
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByText('orders-1')).toBeInTheDocument();
  });
});
