import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';

import { axeViolations, contentWidth, Frame, renderThemed, SCHEMES, settle } from '../../test/browser.tsx';
import { DataPanel } from './DataPanel.tsx';

/**
 * Administration → Data in a real browser: no accessibility violations in either colour scheme, in both
 * views and in the policy dialog, and no layout shift when the rows replace the loading state (the sweep
 * holds every route to 0.01). The data arrives after a delay, as it does from the server.
 */
const STORES = [
  {
    id: 'broker-events',
    label: 'Broker events',
    source: 'core',
    tables: ['broker_event'],
    retention: '72h',
    defaultRetention: '72h',
    minRetention: '1h',
    maxRetention: '90d',
    quotaUnit: 'ROWS',
    quota: 1000,
    quotaWarnPercent: 80,
    quotaUsedPercent: 85,
    rows: 12000,
    bytes: 3_400_000,
    overWarning: true,
  },
  {
    id: 'metric-samples',
    label: 'Metric samples',
    source: 'metrics',
    tables: ['metric_sample'],
    retention: 'forever',
    defaultRetention: '30d',
    minRetention: '1d',
    maxRetention: 'forever',
    quotaUnit: 'BYTES',
    quota: 0,
    quotaWarnPercent: 80,
    rows: 5_000_000,
    bytes: 900_000_000,
    overWarning: false,
    lastPurgeAt: '2026-09-30T02:00:00Z',
    lastPurged: 1200,
  },
];

const TABLES = [
  {
    schema: 'public',
    name: 'broker_event',
    rows: 12000,
    deadRows: 10,
    deadPercent: 0,
    bytes: 3_400_000,
    partitioned: false,
    missingPartitions: [],
    problems: [],
  },
  {
    schema: 'public',
    name: 'metric_sample',
    rows: 5_000_000,
    deadRows: 800_000,
    deadPercent: 14,
    bytes: 900_000_000,
    partitioned: true,
    missingPartitions: ['2026-10-02'],
    problems: ['no partition for 2026-10-02'],
    lastVacuum: '2026-09-29T02:00:00Z',
    growthBytes: 12_000_000,
  },
];

const ME = {
  id: 'u1',
  username: 'admin',
  mustChangePassword: false,
  grants: [{ scopeType: 'GLOBAL', scopeId: null, permissions: ['*'] }],
};

function bodyFor(path: string): unknown {
  if (path.endsWith('/auth/me')) return ME;
  if (path.endsWith('/data/stores')) return { stores: STORES };
  if (path.endsWith('/data/health')) return { tables: TABLES };
  return {};
}

beforeEach(() => {
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const path = new URL(String(input), location.origin).pathname;
    await new Promise((resolve) => setTimeout(resolve, 150));
    return new Response(JSON.stringify(bodyFor(path)), { headers: { 'content-type': 'application/json' } });
  });
});
afterEach(() => vi.unstubAllGlobals());

function page(search: string) {
  const root = createRootRoute();
  const route = createRoute({ getParentRoute: () => root, path: '/admin', component: DataPanel });
  return createRouter({
    routeTree: root.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [`/admin${search}`] }),
  });
}

function Providers({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      {children}
    </QueryClientProvider>
  );
}

/** The layout shift the page adds between mounting and settling, as the browser reports it. */
function watchShift(): () => number {
  let total = 0;
  const observer = new PerformanceObserver((list) => {
    for (const entry of list.getEntries() as unknown as { value: number; hadRecentInput: boolean }[]) {
      if (!entry.hadRecentInput) total += entry.value;
    }
  });
  observer.observe({ type: 'layout-shift', buffered: false });
  return () => {
    observer.takeRecords();
    observer.disconnect();
    return total;
  };
}

const VIEWS = [
  { name: 'retention', search: '', ready: /^Broker events/ },
  { name: 'storage health', search: '?view=health', ready: 'broker_event' },
] as const;

describe.each(VIEWS)('Data, $name', ({ search, ready }) => {
  describe.each(SCHEMES)('in the %s scheme at 1280 px', (scheme) => {
    it('has no accessibility violations and nothing scrolling sideways', async () => {
      const { container } = renderThemed(
        <Providers>
          <Frame width={contentWidth(1280)} height={900}>
            <RouterProvider router={page(search)} />
          </Frame>
        </Providers>,
        scheme,
      );
      await screen.findByRole('rowheader', { name: ready }, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}:${container.querySelectorAll('[role="row"], tr').length}`);

      expect(await axeViolations(container)).toEqual([]);
      expect(container.scrollWidth).toBeLessThanOrEqual(container.clientWidth + 1);
    });
  });

  describe.each([
    { name: '1280 px', width: () => contentWidth(1280) },
    { name: '200% zoom', width: () => 560 },
  ])('at $name', ({ width }) => {
    it('swaps loading for rows without moving what is already on the page', async () => {
      const done = watchShift();
      const { container } = renderThemed(
        <Providers>
          <Frame width={width()} height={900}>
            <RouterProvider router={page(search)} />
          </Frame>
        </Providers>,
        'light',
      );
      await screen.findByRole('rowheader', { name: ready }, { timeout: 5000 });
      await settle(() => `${container.scrollHeight}:${container.querySelectorAll('[role="row"], tr').length}`);
      await new Promise((resolve) => setTimeout(resolve, 500));

      expect(done()).toBeLessThanOrEqual(0.01);
    });
  });
});

describe('The policy dialog', () => {
  describe.each(SCHEMES)('in the %s scheme', (scheme) => {
    it('has no accessibility violations, with a field in error', async () => {
      renderThemed(
        <Providers>
          <Frame width={contentWidth(1280)} height={900}>
            <RouterProvider router={page('')} />
          </Frame>
        </Providers>,
        scheme,
      );
      await userEvent.click(await screen.findByRole('rowheader', { name: /^Broker events/ }, { timeout: 5000 }));
      const dialog = await screen.findByRole('dialog', { name: 'Broker events policy' });
      await userEvent.type(screen.getByLabelText('Warn at (% of quota)'), '0');
      await userEvent.clear(screen.getByLabelText('Warn at (% of quota)'));
      await userEvent.tab();
      await screen.findByText('Enter a whole number from 1 to 100.');
      // The dialog fades in; a colour read mid-fade is a blend with the page behind it, not the dialog's own.
      await settle(() => `${getComputedStyle(dialog).opacity}:${dialog.getBoundingClientRect().top}`);

      expect(await axeViolations(dialog)).toEqual([]);
    });
  });
});
