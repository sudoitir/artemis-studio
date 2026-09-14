import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
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

/** Force a cell to report itself as ellipsized (jsdom does no layout). */
function markClipped(el: HTMLElement) {
  Object.defineProperty(el, 'scrollWidth', { configurable: true, value: 800 });
  Object.defineProperty(el, 'clientWidth', { configurable: true, value: 120 });
}

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

  it('makes free-text cells focusable for the truncation reveal, but not numeric cells', () => {
    renderWithProviders(
      <VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} />,
    );
    const nameCell = screen.getByText('ORDERS').closest('[role="gridcell"]')!;
    const depthCell = screen.getByText('12').closest('[role="gridcell"]')!;
    expect(nameCell).toHaveAttribute('tabindex', '0');
    expect(nameCell).toHaveAttribute('data-full', 'ORDERS');
    expect(depthCell).not.toHaveAttribute('tabindex');
  });

  it('reveals the full value with a copy control when a cell is actually clipped', async () => {
    renderWithProviders(
      <VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} />,
    );
    const cell = screen.getByText('SHIPMENTS').closest('[role="gridcell"]') as HTMLElement;

    // Not clipped yet → focusing it shows nothing.
    fireEvent.focus(cell);
    expect(screen.queryByRole('dialog', { name: /full value/i })).not.toBeInTheDocument();

    markClipped(cell);
    fireEvent.focus(cell);
    const panel = screen.getByRole('dialog', { name: /full value/i });
    expect(within(panel).getByText('SHIPMENTS')).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: /copy/i })).toBeInTheDocument();

    fireEvent.blur(cell);
    expect(screen.queryByRole('dialog', { name: /full value/i })).not.toBeInTheDocument();
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
