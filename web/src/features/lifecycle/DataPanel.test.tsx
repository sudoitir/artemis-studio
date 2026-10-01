import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderAppAt, renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import { HealthTable } from './HealthTable.tsx';
import { RetentionTable } from './RetentionTable.tsx';

function store(over: Record<string, unknown> = {}) {
  return {
    id: 'broker-events',
    label: 'Broker events',
    source: 'core',
    tables: ['broker_event'],
    retention: '72h',
    defaultRetention: '72h',
    minRetention: '1h',
    maxRetention: '90d',
    quotaUnit: 'ROWS',
    quota: 0,
    quotaWarnPercent: 80,
    rows: 12000,
    bytes: 3_400_000,
    overWarning: false,
    ...over,
  };
}

function table(over: Record<string, unknown> = {}) {
  return {
    schema: 'public',
    name: 'broker_event',
    rows: 12000,
    deadRows: 10,
    deadPercent: 0,
    bytes: 3_400_000,
    partitioned: false,
    missingPartitions: [],
    problems: [],
    ...over,
  };
}

function signedIn(permissions: string[]) {
  return http.get('*/api/v1/auth/me', () =>
    HttpResponse.json({
      id: 'u1',
      username: 'ops',
      mustChangePassword: false,
      grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions }],
    }),
  );
}

describe('RetentionTable', () => {
  it('lists every store with its retention and usage', async () => {
    server.use(http.get('*/api/v1/data/stores', () => HttpResponse.json({ stores: [store()] })));
    renderWithProviders(<RetentionTable />);

    const grid = await screen.findByRole('grid', { name: 'Stores' });
    expect(await within(grid).findByText('Broker events')).toBeInTheDocument();
    expect(within(grid).getByText('72 hours')).toBeInTheDocument();
    expect(within(grid).getByText('3.4 MB')).toBeInTheDocument();
  });

  it('says why an empty list is empty', async () => {
    server.use(http.get('*/api/v1/data/stores', () => HttpResponse.json({ stores: [] })));
    renderWithProviders(<RetentionTable />);

    expect(await screen.findByText(/No store is registered/)).toBeInTheDocument();
    expect(screen.getByText(/A store is a table that grows with use/)).toBeInTheDocument();
  });

  it('states the cause when the stores cannot be read', async () => {
    server.use(
      http.get('*/api/v1/data/stores', () =>
        HttpResponse.json({ title: 'Forbidden', detail: 'Access denied' }, { status: 403 }),
      ),
    );
    renderWithProviders(<RetentionTable />);

    expect(await screen.findByRole('alert')).toHaveTextContent('You are not allowed to do this');
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('previews a shorter retention, then shows the server’s range when saving is refused', async () => {
    let previewed: unknown;
    server.use(
      signedIn(['data:read', 'data:write']),
      http.get('*/api/v1/data/stores', () => HttpResponse.json({ stores: [store()] })),
      http.post('*/api/v1/data/stores/broker-events/preview', async ({ request }) => {
        previewed = await request.json();
        return HttpResponse.json({ rows: 9000, bytes: 2_000_000 });
      }),
      http.put('*/api/v1/data/stores/broker-events', () =>
        HttpResponse.json(
          { title: 'Bad Request', detail: 'lifecycle.broker-events.retention must be between 1h and 90d' },
          { status: 400 },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<RetentionTable />);

    await user.click(await screen.findByText('Broker events'));
    const retention = await screen.findByLabelText(/Retention/);
    await user.clear(retention);
    await user.type(retention, '24h');
    await user.click(screen.getByRole('button', { name: 'Preview' }));

    expect(await screen.findByText(/remove about 9,000 rows \(2 MB\)/)).toBeInTheDocument();
    expect(previewed).toEqual({ retention: '24h' });

    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(/must be between 1h and 90d/)).toBeInTheDocument();
  });

  it('rejects a retention without a unit on blur', async () => {
    server.use(
      signedIn(['data:read', 'data:write']),
      http.get('*/api/v1/data/stores', () => HttpResponse.json({ stores: [store()] })),
    );
    const user = userEvent.setup();
    renderWithProviders(<RetentionTable />);

    await user.click(await screen.findByText('Broker events'));
    const retention = await screen.findByLabelText(/Retention/);
    await user.clear(retention);
    await user.type(retention, '24');
    await user.tab();

    expect(await screen.findByText(/Use a number and a unit/)).toBeInTheDocument();
  });

  it('tells a reader without data:write why the stores cannot be edited', async () => {
    server.use(
      signedIn(['data:read']),
      http.get('*/api/v1/data/stores', () => HttpResponse.json({ stores: [store()] })),
    );
    renderWithProviders(<RetentionTable />);

    expect(await screen.findByText(/Changing a policy needs the data:write permission/)).toBeInTheDocument();
  });
});

describe('HealthTable', () => {
  it('puts unhealthy tables first, in words', async () => {
    server.use(
      http.get('*/api/v1/data/health', () =>
        HttpResponse.json({
          tables: [
            table(),
            table({
              name: 'metric_sample',
              partitioned: true,
              missingPartitions: ['2026-10-02'],
              problems: ['no partition for 2026-10-02'],
              bytes: 10,
            }),
          ],
        }),
      ),
    );
    renderWithProviders(<HealthTable />);

    expect(await screen.findByText('1 of 2 tables need attention.', { exact: false })).toBeInTheDocument();
    expect(await screen.findByText('Unhealthy: no partition for 2026-10-02')).toBeInTheDocument();
    expect(screen.getByText('missing 2026-10-02')).toBeInTheDocument();
  });
});

describe('HealthTable states', () => {
  it('teaches what the list holds when Postgres has no statistics yet', async () => {
    server.use(http.get('*/api/v1/data/health', () => HttpResponse.json({ tables: [] })));
    renderWithProviders(<HealthTable />);

    expect(await screen.findByText('No table statistics yet')).toBeInTheDocument();
    expect(screen.getByText(/size, growth, dead rows, vacuum and partitions/)).toBeInTheDocument();
    expect(screen.getByRole('grid', { name: 'Tables' })).toHaveAttribute('aria-rowcount', '1');
  });

  it('states the cause when the health cannot be read, and offers to try again', async () => {
    server.use(http.get('*/api/v1/data/health', () => HttpResponse.json({ title: 'Down' }, { status: 503 })));
    renderWithProviders(<HealthTable />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Studio failed to complete the request');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByText(/tables are healthy/)).not.toBeInTheDocument();
  });
});

describe('Administration → Data', () => {
  it('opens the view the address names, and switching views updates the address', async () => {
    server.use(
      signedIn(['data:read', 'data:write']),
      http.get('*/api/v1/data/stores', () => HttpResponse.json({ stores: [store()] })),
      http.get('*/api/v1/data/health', () => HttpResponse.json({ tables: [table()] })),
    );
    const user = userEvent.setup();
    const { router } = renderAppAt('/admin?tab=data&view=health');

    expect(await screen.findByRole('grid', { name: 'Tables' })).toBeInTheDocument();

    await user.click(screen.getByText('Retention'));
    expect(await screen.findByRole('grid', { name: 'Stores' })).toBeInTheDocument();
    expect(router.state.location.search).toMatchObject({ tab: 'data', view: 'retention' });
  });
});
