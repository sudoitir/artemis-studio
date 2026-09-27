import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Menu } from '@mantine/core';
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

  describe('keyboard (ADR-0108)', () => {
    function Harness({
      data = rows,
      onRowClick,
      selectable,
    }: {
      data?: Q[];
      onRowClick?: (row: Q) => void;
      selectable?: boolean;
    }) {
      const [selected, setSelected] = useState<Set<string>>(new Set());
      const [sort, setSort] = useState<string | undefined>(undefined);
      return (
        <>
          <button type="button">before</button>
          <VirtualTable
            label="Queues"
            columns={columns}
            data={data}
            rowKey={(r) => r.name}
            sort={sort}
            onSortChange={setSort}
            onRowClick={onRowClick}
            selectable={selectable}
            selected={selected}
            onToggleRow={(key) => {
              const next = new Set(selected);
              if (next.has(key)) next.delete(key);
              else next.add(key);
              setSelected(next);
            }}
          />
          <button type="button">after</button>
        </>
      );
    }

    const cellOf = (text: string) => screen.getByText(text).closest('[role="gridcell"]') as HTMLElement;

    it('is one tab stop, named, entering on the first row', async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness />);
      expect(screen.getByRole('grid', { name: 'Queues' })).toBeInTheDocument();

      screen.getByRole('button', { name: 'before' }).focus();
      await user.tab();
      expect(cellOf('ORDERS')).toHaveFocus();
      await user.tab();
      expect(screen.getByRole('button', { name: 'after' })).toHaveFocus();
      await user.tab({ shift: true });
      expect(cellOf('ORDERS')).toHaveFocus();
    });

    it('moves between cells with the arrows, keeping the column', async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness />);
      await user.click(screen.getByRole('button', { name: 'before' }));
      await user.tab();
      await user.keyboard('{ArrowDown}');
      expect(cellOf('SHIPMENTS')).toHaveFocus();
      await user.keyboard('{ArrowRight}');
      expect(cellOf('0')).toHaveFocus();
      await user.keyboard('{ArrowDown}');
      expect(cellOf('431')).toHaveFocus();
      await user.keyboard('{Control>}{Home}{/Control}');
      expect(cellOf('12')).toHaveFocus();
      await user.keyboard('{Home}');
      expect(cellOf('ORDERS')).toHaveFocus();
    });

    it('reaches the header, where Enter sorts', async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness />);
      await user.click(screen.getByRole('button', { name: 'before' }));
      await user.tab();
      await user.keyboard('{ArrowUp}');
      const sortQueue = screen.getByRole('button', { name: /queue/i });
      expect(sortQueue).toHaveFocus();
      await user.keyboard('{Enter}');
      expect(screen.getByRole('columnheader', { name: /queue/i })).toHaveAttribute('aria-sort', 'ascending');
    });

    it('activates a row with Enter and selects it with Space', async () => {
      const user = userEvent.setup();
      const onRowClick = vi.fn();
      renderWithProviders(<Harness onRowClick={onRowClick} selectable />);
      await user.click(screen.getByRole('button', { name: 'before' }));
      await user.tab();
      // With selection on, the grid is still entered on the first value, not on the checkbox.
      expect(cellOf('ORDERS')).toHaveFocus();
      await user.keyboard('{ArrowDown}');
      expect(cellOf('SHIPMENTS')).toHaveFocus();
      await user.keyboard('{Enter}');
      expect(onRowClick).toHaveBeenCalledWith({ name: 'SHIPMENTS', depth: 0 });
      await user.keyboard(' ');
      expect(screen.getByRole('checkbox', { name: 'Select row SHIPMENTS' })).toBeChecked();
    });

    it('keeps focus on the same row when the data is reordered', async () => {
      const user = userEvent.setup();
      const { rerender } = renderWithProviders(<Harness />);
      await user.click(screen.getByRole('button', { name: 'before' }));
      await user.tab();
      await user.keyboard('{ArrowDown}');
      expect(cellOf('SHIPMENTS')).toHaveFocus();
      rerender(<Harness data={[...rows].reverse()} />);
      expect(cellOf('SHIPMENTS')).toHaveFocus();
    });

    it('hands focus to a neighbour when the focused row goes away', async () => {
      const user = userEvent.setup();
      const { rerender } = renderWithProviders(<Harness />);
      await user.click(screen.getByRole('button', { name: 'before' }));
      await user.tab();
      await user.keyboard('{ArrowDown}');
      rerender(<Harness data={rows.filter((r) => r.name !== 'SHIPMENTS')} />);
      await waitFor(() => expect(cellOf('DLQ')).toHaveFocus());
    });

    it('copies the focused cell with Ctrl+C and says so', async () => {
      const user = userEvent.setup();
      const writeText = vi.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
      renderWithProviders(<Harness />);
      await user.click(screen.getByRole('button', { name: 'before' }));
      await user.tab();
      await user.keyboard('{Control>}c{/Control}');
      expect(writeText).toHaveBeenCalledWith('ORDERS');
      expect(await screen.findByText('Copied ORDERS')).toBeInTheDocument();
    });
  });

  describe('row menu (ADR-0107)', () => {
    function WithMenu({ onDelete = () => {} }: { onDelete?: (name: string) => void }) {
      return (
        <VirtualTable
          label="Queues"
          columns={columns}
          data={rows}
          rowKey={(r) => r.name}
          rowMenu={{
            label: (r) => r.name,
            render: (r) => (
              <>
                <Menu.Item>Open {r.name}</Menu.Item>
                <Menu.Item onClick={() => onDelete(r.name)}>Delete {r.name}</Menu.Item>
              </>
            ),
          }}
        />
      );
    }

    it('opens from the row\'s Actions control and returns focus to it', async () => {
      const user = userEvent.setup();
      renderWithProviders(<WithMenu />);
      const trigger = screen.getByRole('button', { name: 'Actions for SHIPMENTS' });
      expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
      await user.click(trigger);
      const menu = await screen.findByRole('menu', { name: 'Actions for SHIPMENTS' });
      expect(within(menu).getByRole('menuitem', { name: 'Delete SHIPMENTS' })).toBeInTheDocument();
      await user.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
      await waitFor(() => expect(screen.getByRole('button', { name: 'Actions for SHIPMENTS' })).toHaveFocus());
    });

    it('opens with Shift+F10 from any cell of the row, and acts from the keyboard', async () => {
      const user = userEvent.setup();
      const onDelete = vi.fn();
      renderWithProviders(<WithMenu onDelete={onDelete} />);
      cellOf('DLQ').focus();
      await user.keyboard('{Shift>}{F10}{/Shift}');
      const menu = await screen.findByRole('menu', { name: 'Actions for DLQ' });
      await waitFor(() => expect(within(menu).getByRole('menuitem', { name: 'Open DLQ' })).toHaveFocus());
      await user.keyboard('{ArrowDown}{Enter}');
      expect(onDelete).toHaveBeenCalledWith('DLQ');
      await waitFor(() => expect(screen.getByRole('button', { name: 'Actions for DLQ' })).toHaveFocus());
    });

    it('opens on right-click, but leaves the browser menu to Shift+right-click', async () => {
      renderWithProviders(<WithMenu />);
      const shifted = fireEvent.contextMenu(cellOf('ORDERS'), { shiftKey: true });
      expect(shifted).toBe(true);
      expect(screen.queryByRole('menu')).not.toBeInTheDocument();

      const handled = fireEvent.contextMenu(cellOf('ORDERS'), { clientX: 40, clientY: 50 });
      expect(handled).toBe(false);
      expect(await screen.findByRole('menu', { name: 'Actions for ORDERS' })).toBeInTheDocument();
    });

    function cellOf(text: string) {
      return screen.getByText(text).closest('[role="gridcell"]') as HTMLElement;
    }
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

  describe('selection', () => {
    function Selectable({ onToggleRow = () => {}, onToggleAll = () => {} }: {
      onToggleRow?: (key: string) => void;
      onToggleAll?: (keys: string[], allSelected: boolean) => void;
    }) {
      const [selected, setSelected] = useState<Set<string>>(new Set());
      return (
        <VirtualTable
          columns={columns}
          data={rows}
          rowKey={(r) => r.name}
          selectable
          selected={selected}
          onToggleRow={(key) => {
            onToggleRow(key);
            const next = new Set(selected);
            if (next.has(key)) next.delete(key);
            else next.add(key);
            setSelected(next);
          }}
          onToggleAll={(keys, allSelected) => {
            onToggleAll(keys, allSelected);
            setSelected(allSelected ? new Set() : new Set(keys));
          }}
        />
      );
    }

    it('toggles one row without clicking the row itself', async () => {
      const user = userEvent.setup();
      const onToggleRow = vi.fn();
      const onRowClick = vi.fn();
      renderWithProviders(
        <VirtualTable
          columns={columns}
          data={rows}
          rowKey={(r) => r.name}
          selectable
          selected={new Set()}
          onToggleRow={onToggleRow}
          onRowClick={onRowClick}
        />,
      );
      await user.click(screen.getByRole('checkbox', { name: 'Select row SHIPMENTS' }));
      expect(onToggleRow).toHaveBeenCalledWith('SHIPMENTS');
      expect(onRowClick).not.toHaveBeenCalled();
    });

    it('marks the header box indeterminate for a partial page, and checked for a full one', async () => {
      const user = userEvent.setup();
      renderWithProviders(<Selectable />);
      const all = () => screen.getByRole('checkbox', { name: /select all on this page/i });

      expect(all()).not.toBeChecked();
      expect(all()).not.toHaveAttribute('data-indeterminate');

      await user.click(screen.getByRole('checkbox', { name: 'Select row ORDERS' }));
      expect(all()).not.toBeChecked();
      expect(all()).toHaveAttribute('data-indeterminate', 'true');

      await user.click(screen.getByRole('checkbox', { name: 'Select row SHIPMENTS' }));
      await user.click(screen.getByRole('checkbox', { name: 'Select row DLQ' }));
      expect(all()).toBeChecked();
      expect(all()).toHaveAccessibleName('Deselect all on this page');
    });

    it('selects the whole page, then clears it, through the header box', async () => {
      const user = userEvent.setup();
      const onToggleAll = vi.fn();
      renderWithProviders(<Selectable onToggleAll={onToggleAll} />);

      await user.click(screen.getByRole('checkbox', { name: 'Select all on this page' }));
      expect(onToggleAll).toHaveBeenLastCalledWith(['ORDERS', 'SHIPMENTS', 'DLQ'], false);
      for (const name of ['ORDERS', 'SHIPMENTS', 'DLQ']) {
        expect(screen.getByRole('checkbox', { name: `Select row ${name}` })).toBeChecked();
      }

      await user.click(screen.getByRole('checkbox', { name: 'Deselect all on this page' }));
      expect(onToggleAll).toHaveBeenLastCalledWith(['ORDERS', 'SHIPMENTS', 'DLQ'], true);
      expect(screen.getByRole('checkbox', { name: 'Select row ORDERS' })).not.toBeChecked();
    });
  });

  describe('column widths (ADR-0116)', () => {
    it('widens a fixed column whose value needs more than its declared width', () => {
      vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
        return (this.textContent ?? '').length * 10;
      });
      const typed: GridColumn<Q>[] = [
        { id: 'name', header: 'Queue', accessor: (r) => r.name },
        { id: 'type', header: 'T', accessor: () => 'MULTICAST', width: 60 },
      ];
      renderWithProviders(<VirtualTable label="Queues" columns={typed} data={rows} rowKey={(r) => r.name} />);
      expect(screen.getByRole('grid').style.getPropertyValue('--as-cols')).toBe('minmax(180px, 1fr) 92px');
    });

    const colsOf = () => screen.getByRole('grid').style.getPropertyValue('--as-cols');
    const long = { name: 'X'.repeat(30), depth: 1 };

    afterEach(() => {
      localStorage.clear();
      vi.restoreAllMocks();
    });

    it('fits a free-text column to its widest value, within the cap', () => {
      // jsdom does no layout: a cell needs ten pixels per character.
      vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
        return (this.textContent ?? '').length * 10;
      });
      renderWithProviders(<VirtualTable label="Queues" columns={columns} data={[...rows, long]} rowKey={(r) => r.name} />);
      expect(colsOf()).toContain('minmax(302px, 1fr)');
    });

    it('never fits a column below the free-text floor or past the cap', () => {
      const spy = vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
        return (this.textContent ?? '').length;
      });
      const { unmount } = renderWithProviders(
        <VirtualTable label="Queues" columns={columns} data={[long]} rowKey={(r) => r.name} />,
      );
      expect(colsOf()).toBe('minmax(180px, 1fr) minmax(180px, 1fr)');
      unmount();

      spy.mockImplementation(function (this: HTMLElement) {
        return (this.textContent ?? '').length * 100;
      });
      renderWithProviders(<VirtualTable label="Queues" columns={columns} data={[long]} rowKey={(r) => r.name} />);
      expect(colsOf()).toContain('minmax(480px, 1fr)');
    });

    it('resizes a column from its header with Ctrl+Shift+Arrow, announces it and remembers it', async () => {
      localStorage.setItem('as.grid.queues', JSON.stringify({ name: 200 }));
      renderWithProviders(
        <VirtualTable label="Queues" storageKey="queues" columns={columns} data={rows} rowKey={(r) => r.name} onSortChange={vi.fn()} />,
      );
      expect(colsOf()).toContain('200px');
      const header = screen.getByRole('button', { name: /queue/i });
      header.focus();
      fireEvent.keyDown(header, { key: 'ArrowRight', ctrlKey: true, shiftKey: true });
      fireEvent.keyDown(header, { key: 'ArrowRight', ctrlKey: true, shiftKey: true });
      fireEvent.keyDown(header, { key: 'ArrowLeft', ctrlKey: true, shiftKey: true });

      expect(colsOf()).toContain('216px');
      expect(await screen.findByText('Queue column, 216 pixels')).toBeInTheDocument();
      expect(JSON.parse(localStorage.getItem('as.grid.queues')!)).toEqual({ name: 216 });
      // Focus did not move: the grid is still one tab stop on the same header.
      expect(header).toHaveFocus();
    });

    it('describes the resize keys on every header', () => {
      renderWithProviders(<VirtualTable label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
      for (const header of screen.getAllByRole('columnheader')) {
        expect(header.getAttribute('aria-description')).toMatch(/Ctrl\+Shift\+Left or Right/);
      }
    });

    it('ignores stored widths it cannot trust', () => {
      localStorage.setItem('as.grid.queues', JSON.stringify({ name: 'wide', gone: 300, depth: 1e9 }));
      renderWithProviders(<VirtualTable label="Queues" storageKey="queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
      expect(colsOf()).not.toMatch(/(^|\s)\d+px/);

      localStorage.setItem('as.grid.other', '{not json');
      renderWithProviders(<VirtualTable label="Other" storageKey="other" columns={columns} data={rows} rowKey={(r) => r.name} />);
      expect(screen.getByRole('grid', { name: 'Other' })).toBeInTheDocument();
    });
  });
});
