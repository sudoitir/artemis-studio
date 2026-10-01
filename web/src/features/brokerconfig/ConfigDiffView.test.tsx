import { describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { server } from '../../test/setup.ts';
import type { ConfigDiffView as Diff, ConfigEntryView } from './api.ts';

vi.mock('@tanstack/react-router', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-router')>()),
  useParams: () => ({ clusterId: 'c1' }),
}));

const { ConfigDiffView } = await import('./ConfigDiffView.tsx');

const opt = { hidden: true } as const;

const endpoint = (id: string, name: string, manageable = true) => ({
  id,
  name,
  haRole: 'PRIMARY',
  state: 'UP',
  active: true,
  discovered: true,
  manualOverride: false,
  manageable,
});

const TOPOLOGY = {
  clusterId: 'c1',
  nodes: [
    {
      splitBrain: 'NONE',
      replicationBehind: false,
      endpoints: [endpoint('n-a', 'broker-1'), endpoint('n-b', 'broker-2')],
    },
    { splitBrain: 'NONE', replicationBehind: false, endpoints: [endpoint('n-c', 'broker-3', false)] },
  ],
};

const entry = (over: Partial<ConfigEntryView>): ConfigEntryView => ({
  key: 'k',
  left: 'a',
  right: 'a',
  status: 'SAME',
  statusWord: 'same',
  classification: 'CONFIGURATION',
  drift: false,
  ...over,
});

const side = (nodeId: string, nodeName: string, over = {}) => ({
  nodeId,
  nodeName,
  available: true,
  active: true,
  reducedSurface: false,
  ...over,
});

function diff(over: Partial<Diff> = {}): Diff {
  return {
    clusterId: 'c1',
    left: side('n-a', 'broker-1'),
    right: side('n-b', 'broker-2'),
    comparable: true,
    driftCount: 1,
    matchesCompared: 1,
    matchesAvailable: 1,
    note: null,
    sections: [
      {
        section: 'broker',
        label: 'Broker',
        driftCount: 1,
        entries: [
          entry({
            key: 'max-disk-usage',
            left: '90',
            right: '80',
            status: 'DIFFERENT',
            statusWord: 'differs',
            drift: true,
          }),
          entry({
            key: 'name',
            left: 'broker-1',
            right: 'broker-2',
            classification: 'EXPECTED',
            statusWord: 'differs',
          }),
          entry({
            key: 'message-counter',
            left: '4',
            right: '9',
            classification: 'UNCLASSIFIED',
            statusWord: 'differs',
          }),
          entry({ key: 'journal-type', left: 'NIO', right: 'NIO' }),
        ],
      },
      { section: 'addressSettings', label: 'Address settings', driftCount: 0, entries: [] },
    ],
    ...over,
  };
}

function serve(d: Diff | Response = diff(), seen?: (params: URLSearchParams) => void) {
  server.use(
    http.get('*/api/v1/clusters/c1/topology', () => HttpResponse.json(TOPOLOGY)),
    http.get('*/api/v1/clusters/c1/config-diff', ({ request }) => {
      seen?.(new URL(request.url).searchParams);
      return d instanceof Response ? d : HttpResponse.json(d);
    }),
  );
}

describe('ConfigDiffView', () => {
  it('shows the pair, the drift count, and every key with its status in words', async () => {
    serve();
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByRole('heading', { name: 'broker-1 ↔ broker-2' })).toBeInTheDocument();
    // The pair's total and the section's own count.
    expect(screen.getAllByText('1 drift')).toHaveLength(2);
    expect(screen.getByText('4 keys')).toBeInTheDocument();

    // Only real drift is flagged; expected and unclassified differences are labelled, not counted.
    const drift = screen.getByRole('row', { name: /max-disk-usage/ });
    expect(drift).toHaveTextContent('90');
    expect(drift).toHaveTextContent('80');
    expect(within(drift).getByText('drift')).toBeInTheDocument();
    expect(
      within(screen.getByRole('row', { name: /^name/ })).getByTitle('Correct by design for two distinct nodes'),
    ).toHaveTextContent('expected');
    expect(within(screen.getByRole('row', { name: /message-counter/ })).getByText('unclassified')).toBeInTheDocument();
    expect(within(screen.getByRole('row', { name: /journal-type/ })).queryByText('drift')).toBeNull();
  });

  it('says a pair with no drift has none, and a single drift in the singular', async () => {
    serve(diff({ driftCount: 0, sections: [] }));
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText('no drift')).toBeInTheDocument();
  });

  it('counts several drifts and a one-key section in the singular', async () => {
    const d = diff({ driftCount: 2 });
    d.sections = [
      {
        section: 'broker',
        label: 'Broker',
        driftCount: 0,
        entries: [entry({ key: 'only-key' })],
      },
    ];
    serve(d);
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText('2 drifts')).toBeInTheDocument();
    expect(screen.getByText('1 key')).toBeInTheDocument();
  });

  it('shows a missing value as a dash and says so for an empty section', async () => {
    const d = diff();
    d.sections = [
      {
        section: 'broker',
        label: 'Broker',
        driftCount: 1,
        entries: [entry({ key: 'only-left', left: 'x', right: null, drift: true, statusWord: 'only on broker-1' })],
      },
      { section: 'addressSettings', label: 'Address settings', driftCount: 0, entries: [] },
    ];
    serve(d);
    renderWithProviders(<ConfigDiffView />);

    const row = await screen.findByRole('row', { name: /only-left/ });
    expect(within(row).getByText('—')).toBeInTheDocument();
    expect(within(row).getByText('only on broker-1')).toBeInTheDocument();
    expect(screen.getByText('Nothing to compare in this section.')).toBeInTheDocument();
    expect(screen.getAllByText('1 drift')).toHaveLength(2);
  });

  it('refuses a half-diff: an unreachable side is named with its reason and no section is shown', async () => {
    serve(
      diff({
        comparable: false,
        note: 'Only one node answered.',
        right: side('n-b', 'broker-2', { available: false, unavailableReason: 'connection refused' }),
      }),
    );
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText('No comparison shown')).toBeInTheDocument();
    expect(screen.getByText('Only one node answered.')).toBeInTheDocument();
    expect(screen.getByText('broker-2 unavailable')).toBeInTheDocument();
    expect(screen.getByText(/connection refused/)).toBeInTheDocument();
    // No drift badge for a pair that could not be compared, and no accordion of half-known keys.
    expect(screen.queryByText(/drift/i, { selector: '.mantine-Badge-label' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Broker/ })).toBeNull();
  });

  it('shows the note beside a comparable pair without treating it as a refusal', async () => {
    serve(diff({ note: 'Compared 1 of 3 address matches.' }));
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByText('Compared 1 of 3 address matches.')).toBeInTheDocument();
    expect(screen.queryByText('No comparison shown')).toBeNull();
  });

  it('states why the comparison failed when the request fails', async () => {
    serve(
      HttpResponse.json({ title: 'Cluster unreachable', detail: 'No node answered the comparison.' }, { status: 502 }),
    );
    renderWithProviders(<ConfigDiffView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Studio failed to complete the request');
    expect(within(alert).getByText('No node answered the comparison.')).toBeInTheDocument();
    // The failure offers the comparison again.
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeEnabled();
  });

  it('narrows to drift only, keeping the sections', async () => {
    serve();
    const user = userEvent.setup();
    renderWithProviders(<ConfigDiffView />);

    await screen.findByRole('row', { name: /journal-type/ });
    await user.click(screen.getByRole('switch', { name: 'Drift only' }));

    expect(screen.getByRole('row', { name: /max-disk-usage/ })).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /journal-type/ })).toBeNull();
    expect(screen.queryByRole('row', { name: /message-counter/ })).toBeNull();
  });

  it('picks each side from the nodes, disabling those Studio cannot manage, and asks for that pair', async () => {
    const seen: string[] = [];
    serve(diff(), (p) => seen.push(p.toString()));
    const user = userEvent.setup();
    renderWithProviders(<ConfigDiffView />);

    await screen.findByRole('heading', { name: 'broker-1 ↔ broker-2' });
    expect(seen).toEqual(['']);

    await user.click(screen.getByRole('combobox', { name: 'Left node' }));
    const leftList = await screen.findByRole('listbox', { name: 'Left node', ...opt });
    expect(within(leftList).getByRole('option', { name: 'broker-3', ...opt })).toHaveAttribute(
      'data-combobox-disabled',
      'true',
    );
    await user.click(within(leftList).getByRole('option', { name: 'broker-2', ...opt }));
    await waitFor(() => expect(seen).toContain('left=n-b'));

    await user.click(screen.getByRole('combobox', { name: 'Right node' }));
    const rightList = await screen.findByRole('listbox', { name: 'Right node', ...opt });
    await user.click(within(rightList).getByRole('option', { name: 'broker-1', ...opt }));
    await waitFor(() => expect(seen).toContain('left=n-b&right=n-a'));
  });

  it('is one page: a single h1, the pair as its h2 and each section as an h3', async () => {
    serve();
    renderWithProviders(<ConfigDiffView />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Config diff' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(await screen.findByRole('heading', { level: 2, name: 'broker-1 ↔ broker-2' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: /^Broker/ })).toBeInTheDocument();
  });

  it('draws each section as a native table whose first column heads its rows, with no scroll box around it', async () => {
    serve();
    renderWithProviders(<ConfigDiffView />);

    const table = await screen.findByRole('table', { name: 'Broker configuration of the two nodes' });
    expect(within(table).getByRole('rowheader', { name: 'max-disk-usage' })).toBeInTheDocument();
    for (const header of ['Key', 'Left', 'Right', 'Status']) {
      expect(within(table).getByRole('columnheader', { name: header })).toBeInTheDocument();
    }
    expect(table.closest('[tabindex]')).toBeNull();
  });

  it('says a section with no drift is filtered, not empty, and offers to show every key again', async () => {
    const d = diff();
    d.sections = [
      { section: 'broker', label: 'Broker', driftCount: 1, entries: diff().sections[0].entries },
      {
        section: 'addressSettings',
        label: 'Address settings',
        driftCount: 0,
        entries: [entry({ key: 'agreeing-key' })],
      },
    ];
    serve(d);
    const user = userEvent.setup();
    renderWithProviders(<ConfigDiffView />);

    await screen.findByRole('row', { name: /agreeing-key/ });
    await user.click(screen.getByRole('switch', { name: 'Drift only' }));

    expect(screen.getByText('No drift in this section')).toBeInTheDocument();
    expect(screen.queryByText('Nothing to compare in this section.')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(screen.getByRole('switch', { name: 'Drift only' })).not.toBeChecked();
    expect(await screen.findByRole('row', { name: /agreeing-key/ })).toBeInTheDocument();
  });

  it('names an unreachable side as unreachable, not as an empty comparison', async () => {
    serve(
      diff({
        comparable: false,
        note: 'Only one node answered.',
        right: side('n-b', 'broker-2', { available: false, unavailableReason: 'connection refused' }),
      }),
    );
    renderWithProviders(<ConfigDiffView />);

    const unreachable = await screen.findByRole('list', { name: 'Nodes that could not be reached' });
    expect(within(unreachable).getByText('broker-2')).toBeInTheDocument();
  });

  it('states why the nodes could not be listed, and lists them again on retry', async () => {
    let attempts = 0;
    server.use(
      http.get('*/api/v1/clusters/c1/topology', () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ title: 'Down', detail: 'The topology is not answering.' }, { status: 503 })
          : HttpResponse.json(TOPOLOGY);
      }),
      http.get('*/api/v1/clusters/c1/config-diff', () => HttpResponse.json(diff())),
    );
    const user = userEvent.setup();
    renderWithProviders(<ConfigDiffView />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The topology is not answering.');
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });
});
