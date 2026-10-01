import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';

import { paged } from '../../kernel/api/paging.ts';
import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { AlertFiringView } from './api.ts';
import { FiringPanel } from './FiringPanel.tsx';

const firing = (seq: number, over: Partial<AlertFiringView> = {}): AlertFiringView => ({
  seq,
  ruleId: 'r-1',
  clusterId: 'c-1',
  ruleName: `Backlog ${seq}`,
  subjectKey: `orders-${seq}`,
  severity: 'CRITICAL',
  startedAt: '2026-09-11T10:00:00Z',
  resolvedAt: null,
  value: 1200,
  ...over,
});

describe('FiringPanel', () => {
  it('teaches what an empty list means', async () => {
    server.use(http.get('*/api/v1/clusters/c-1/alerts/firing', () => HttpResponse.json(paged([]))));
    renderWithProviders(<FiringPanel clusterId="c-1" />);

    expect(await screen.findByText('Nothing is firing')).toBeInTheDocument();
    expect(screen.getByText(/Every enabled rule is currently OK/)).toBeInTheDocument();
  });

  it('says the firing alerts could not be loaded instead of saying nothing is firing', async () => {
    server.use(
      http.get('*/api/v1/clusters/c-1/alerts/firing', () =>
        HttpResponse.json({ title: 'Forbidden', detail: 'Not allowed.', permission: 'alert:read' }, { status: 403 }),
      ),
    );
    renderWithProviders(<FiringPanel clusterId="c-1" />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('alert:read');
    expect(screen.queryByText('Nothing is firing')).not.toBeInTheDocument();
  });

  it('lists each firing with its severity in words and marks an installation alert', async () => {
    server.use(
      http.get('*/api/v1/clusters/c-1/alerts/firing', () =>
        HttpResponse.json(
          paged([firing(1), firing(2, { clusterId: null, ruleName: 'Quota watch', severity: 'INFO', value: null })]),
        ),
      ),
    );
    renderWithProviders(<FiringPanel clusterId="c-1" />);

    const row = await screen.findByRole('row', { name: /Backlog 1/ });
    expect(within(row).getByText('critical')).toBeInTheDocument();
    expect(within(row).getByText('orders-1')).toBeInTheDocument();
    const installation = screen.getByRole('row', { name: /Quota watch/ });
    expect(within(installation).getByText('Installation')).toBeInTheDocument();
    expect(within(installation).getByText('info')).toBeInTheDocument();
    expect(within(row).queryByText('Installation')).not.toBeInTheDocument();
  });
});
