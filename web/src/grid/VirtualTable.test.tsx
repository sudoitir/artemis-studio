import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../test/render.tsx';
import { VirtualTable, type GridColumn } from './VirtualTable.tsx';

interface Q {
  name: string;
  depth: number;
}

const rows: Q[] = [
  { name: 'ORDERS', depth: 12 },
  { name: 'SHIPMENTS', depth: 0 },
  { name: 'DLQ', depth: 431 },
];

const columns: GridColumn<Q>[] = [
  { id: 'name', header: 'Queue', accessor: (r) => r.name, sortKey: 'name' },
  { id: 'depth', header: 'Depth', accessor: (r) => r.depth, numeric: true, sortKey: 'depth' },
];

describe('VirtualTable', () => {
  it('renders a row per datum', () => {
    renderWithProviders(
      <VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} />,
    );
    expect(screen.getByText('ORDERS')).toBeInTheDocument();
    expect(screen.getByText('SHIPMENTS')).toBeInTheDocument();
    expect(screen.getByText('DLQ')).toBeInTheDocument();
  });

  it('shows the empty label when there is no data', () => {
    renderWithProviders(
      <VirtualTable
        columns={columns}
        data={[]}
        rowKey={(r) => r.name}
        emptyLabel="No queues match"
      />,
    );
    expect(screen.getByText('No queues match')).toBeInTheDocument();
  });

  it('flips aria-sort as the sort prop cycles, and reports the next value on click', async () => {
    const user = userEvent.setup();
    const seen: (string | undefined)[] = [];

    function Harness() {
      const [sort, setSort] = useState<string | undefined>(undefined);
      return (
        <VirtualTable
          columns={columns}
          data={rows}
          rowKey={(r) => r.name}
          sort={sort}
          onSortChange={(s) => {
            seen.push(s);
            setSort(s);
          }}
        />
      );
    }

    renderWithProviders(<Harness />);
    const depthHeader = () => screen.getByRole('columnheader', { name: /depth/i });

    expect(depthHeader()).toHaveAttribute('aria-sort', 'none');

    await user.click(within(depthHeader()).getByRole('button', { name: /depth/i }));
    expect(depthHeader()).toHaveAttribute('aria-sort', 'ascending');

    await user.click(within(depthHeader()).getByRole('button', { name: /depth/i }));
    expect(depthHeader()).toHaveAttribute('aria-sort', 'descending');

    await user.click(within(depthHeader()).getByRole('button', { name: /depth/i }));
    expect(depthHeader()).toHaveAttribute('aria-sort', 'none');

    expect(seen).toEqual(['depth', '-depth', undefined]);
  });

  it('calls onRowClick with the row', async () => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    renderWithProviders(
      <VirtualTable
        columns={columns}
        data={rows}
        rowKey={(r) => r.name}
        onRowClick={onRowClick}
      />,
    );
    await user.click(screen.getByText('DLQ'));
    expect(onRowClick).toHaveBeenCalledWith({ name: 'DLQ', depth: 431 });
  });
});
