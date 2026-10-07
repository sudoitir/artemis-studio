import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import type { Column } from './columns.ts';
import { DataTable, STATIC_ROW_LIMIT } from './DataTable.tsx';
import { fakeLayout } from './fakeLayout.ts';

interface Node {
  id: string;
  address: string;
  messages: number;
}

const nodes: Node[] = [
  { id: 'primary', address: 'orders.in', messages: 12 },
  { id: 'backup', address: 'orders.out', messages: 0 },
];

const columns: Column<Node>[] = [
  {
    id: 'id',
    header: 'Node',
    accessor: (n) => n.id,
    kind: 'text',
    priority: 'essential',
    sortKey: 'id',
    cell: (n) => <a href={`#${n.id}`}>{n.id}</a>,
  },
  { id: 'address', header: 'Address', accessor: (n) => n.address, kind: 'identifier', priority: 'high' },
  { id: 'messages', header: 'Messages', accessor: (n) => n.messages, kind: 'number', priority: 'high' },
];

function Static(props: Partial<Parameters<typeof DataTable<Node>>[0]> & { caption?: string }) {
  return (
    <DataTable<Node>
      variant="static"
      label="Nodes"
      columns={columns}
      data={nodes}
      rowKey={(n) => n.id}
      empty={<p>No nodes registered</p>}
      {...(props as object)}
    />
  );
}

describe('DataTable: static variant', () => {
  afterEach(() => localStorage.clear());

  it('drops the Columns control and its toolbar when asked to', () => {
    renderWithProviders(<Static columnsMenu={false} />);

    expect(screen.getByRole('table', { name: 'Nodes' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Columns/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Nodes controls' })).not.toBeInTheDocument();
  });

  it('is a native table, named, with column headers and a header for each row', () => {
    renderWithProviders(<Static />);

    expect(screen.getByRole('table', { name: 'Nodes' })).toBeInTheDocument();
    expect(screen.queryByRole('grid')).not.toBeInTheDocument();
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Node', 'Address', 'Messages']);
    expect(screen.getByRole('rowheader', { name: 'primary' })).toHaveAttribute('scope', 'row');
    expect(screen.getByRole('cell', { name: 'orders.in' })).toBeInTheDocument();
    expect(screen.getByRole('cell', { name: '12' })).toBeInTheDocument();
  });

  it('scrolls in a named region once it holds more rows than maxRows, and not before', () => {
    const { unmount } = renderWithProviders(<Static height={{ maxRows: nodes.length }} />);
    expect(screen.queryByRole('region', { name: /scrollable/ })).not.toBeInTheDocument();
    unmount();

    renderWithProviders(<Static height={{ maxRows: nodes.length - 1 }} />);
    const region = screen.getByRole('region', { name: 'Nodes, scrollable' });
    expect(region).toHaveAttribute('tabindex', '0');
    expect(within(region).getByRole('table')).toBeInTheDocument();
  });

  it('names the table by its caption when it has one', () => {
    renderWithProviders(<Static caption="Broker nodes" />);
    expect(screen.getByRole('table', { name: 'Broker nodes' })).toBeInTheDocument();
  });

  it('leaves the keyboard to the controls in its cells', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <button type="button">before</button>
        <Static />
        <button type="button">after</button>
      </>,
    );
    await user.click(screen.getByRole('button', { name: 'before' }));
    await user.tab();
    expect(screen.getByRole('button', { name: 'Columns' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'primary' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('link', { name: 'backup' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'after' })).toHaveFocus();
  });

  it('sorts through the URL owner, like the grid', async () => {
    const user = userEvent.setup();
    const seen: (string | undefined)[] = [];
    function Harness() {
      const [sort, setSort] = useState<string | undefined>(undefined);
      return (
        <Static
          sort={sort}
          onSortChange={(next: string | undefined) => {
            seen.push(next);
            setSort(next);
          }}
        />
      );
    }
    renderWithProviders(<Harness />);
    const header = () => screen.getByRole('columnheader', { name: /node/i });
    expect(header()).toHaveAttribute('aria-sort', 'none');

    await user.click(within(header()).getByRole('button'));
    expect(header()).toHaveAttribute('aria-sort', 'ascending');
    await user.click(within(header()).getByRole('button'));
    expect(header()).toHaveAttribute('aria-sort', 'descending');
    expect(seen).toEqual(['id', '-id']);
  });

  it('shows the empty state beside the table, with its header', () => {
    renderWithProviders(<Static data={[]} />);
    expect(screen.getByText('No nodes registered')).toBeInTheDocument();
    expect(screen.getByRole('table')).not.toContainElement(screen.getByText('No nodes registered'));
    expect(screen.getByRole('columnheader', { name: /node/i })).toBeInTheDocument();
  });

  it('shows hidden placeholder rows while loading', () => {
    renderWithProviders(<Static data={[]} loading />);
    expect(screen.getByRole('table')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading Nodes')).toBeInTheDocument();
    expect(screen.queryByText('No nodes registered')).not.toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(1);
  });

  it('puts the long middle of an identifier behind an ellipsis and keeps its tail', () => {
    const long = 'DLQ.orders.region-eu-west.0001a2b3c4d5';
    renderWithProviders(<Static data={[{ id: 'primary', address: long, messages: 1 }]} />);
    const cell = screen.getByText(long.slice(-12)).closest('td') as HTMLElement;
    expect(cell).toHaveAttribute('title', long);
    expect(cell).toHaveTextContent(long);
  });

  it('draws the grid instead above 200 rows, so a list that grows does not fall off a cliff', () => {
    const many = Array.from({ length: STATIC_ROW_LIMIT + 1 }, (_, i) => ({ id: `n${i}`, address: 'a', messages: i }));
    renderWithProviders(<Static data={many} />);
    expect(screen.getByRole('grid', { name: 'Nodes' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    const edge = many.slice(0, STATIC_ROW_LIMIT);
    renderWithProviders(<Static data={edge} label="Edge" />);
    expect(screen.getByRole('table', { name: 'Edge' })).toBeInTheDocument();
  });

  it('hides its least important columns when they do not fit, with the same solver as the grid', () => {
    const restore = fakeLayout(200);
    try {
      renderWithProviders(<Static />);
      expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Node']);
      expect(screen.getByRole('button', { name: /hidden/ })).toBeInTheDocument();
    } finally {
      restore();
    }
  });

  it('keeps a stable row when the data identity is unchanged', () => {
    const draws = vi.fn();
    const counted: Column<Node>[] = [{ ...columns[0], cell: (n) => (draws(n.id), n.id) }];
    const { rerender } = renderWithProviders(<Static columns={counted} />);
    const before = draws.mock.calls.length;
    rerender(<Static columns={counted} />);
    expect(draws.mock.calls).toHaveLength(before);
  });
});
