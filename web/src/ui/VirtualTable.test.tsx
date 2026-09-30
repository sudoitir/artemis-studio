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
    renderWithProviders(<VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} />);
    expect(screen.getByText('ORDERS')).toBeInTheDocument();
    expect(screen.getByText('SHIPMENTS')).toBeInTheDocument();
    expect(screen.getByText('DLQ')).toBeInTheDocument();
  });

  it('shows the empty label when there is no data', () => {
    renderWithProviders(
      <VirtualTable columns={columns} data={[]} rowKey={(r) => r.name} emptyLabel="No queues match" />,
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

    it("opens from the row's Actions control and returns focus to it", async () => {
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
    renderWithProviders(<VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} />);
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
    renderWithProviders(<VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} onRowClick={onRowClick} />);
    await user.click(screen.getByText('DLQ'));
    expect(onRowClick).toHaveBeenCalledWith({ name: 'DLQ', depth: 431 });
  });

  describe('selection', () => {
    function Selectable({
      onToggleRow = () => {},
      onToggleAll = () => {},
    }: {
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
    afterEach(() => {
      localStorage.clear();
      vi.restoreAllMocks();
    });

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

    it('fits a free-text column to its widest value, within the cap', () => {
      // jsdom does no layout: a cell needs ten pixels per character.
      vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
        return (this.textContent ?? '').length * 10;
      });
      renderWithProviders(
        <VirtualTable label="Queues" columns={columns} data={[...rows, long]} rowKey={(r) => r.name} />,
      );
      expect(colsOf()).toContain('minmax(302px, 1fr)');
    });

    it('never fits a column below the free-text floor or past the cap', () => {
      const spy = vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (
        this: HTMLElement,
      ) {
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
        <VirtualTable
          label="Queues"
          storageKey="queues"
          columns={columns}
          data={rows}
          rowKey={(r) => r.name}
          onSortChange={vi.fn()}
        />,
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
      renderWithProviders(
        <VirtualTable label="Queues" storageKey="queues" columns={columns} data={rows} rowKey={(r) => r.name} />,
      );
      expect(colsOf()).not.toMatch(/(^|\s)\d+px/);

      localStorage.setItem('as.grid.other', '{not json');
      renderWithProviders(
        <VirtualTable label="Other" storageKey="other" columns={columns} data={rows} rowKey={(r) => r.name} />,
      );
      expect(screen.getByRole('grid', { name: 'Other' })).toBeInTheDocument();
    });
  });
});

describe('VirtualTable: cell values', () => {
  interface V {
    id: string;
    value: unknown;
  }
  const vcols: GridColumn<V>[] = [
    { id: 'id', header: 'Id', accessor: (r) => r.id },
    { id: 'value', header: 'Value', accessor: (r) => r.value },
  ];
  const cellOf = (text: string) => screen.getByText(text).closest('[role="gridcell"]') as HTMLElement;

  it('titles a cell with its text or number, and leaves anything else untitled', () => {
    renderWithProviders(
      <VirtualTable
        columns={vcols}
        data={[
          { id: 'a', value: 'text' },
          { id: 'b', value: 42 },
          { id: 'c', value: 7n },
          { id: 'd', value: '' },
          { id: 'e', value: true },
          { id: 'f', value: null },
        ]}
        rowKey={(r) => r.id}
      />,
    );

    const valueCell = (rowId: string) => cellOf(rowId).nextElementSibling as HTMLElement;
    expect(valueCell('a')).toHaveAttribute('title', 'text');
    expect(valueCell('b')).toHaveAttribute('title', '42');
    expect(valueCell('c')).toHaveAttribute('title', '7');
    expect(valueCell('d')).not.toHaveAttribute('title');
    expect(valueCell('e')).not.toHaveAttribute('title');
    expect(valueCell('e')).toHaveTextContent('true');
    expect(valueCell('f')).toBeEmptyDOMElement();
  });

  it('draws what a column’s own cell renderer returns', () => {
    const custom: GridColumn<V>[] = [{ id: 'id', header: 'Id', accessor: (r) => r.id, cell: (r) => <b>{r.id}!</b> }];
    renderWithProviders(<VirtualTable columns={custom} data={[{ id: 'a', value: 1 }]} rowKey={(r) => r.id} />);
    expect(screen.getByText('a!').tagName).toBe('B');
  });
});

describe('VirtualTable: pointer resizing', () => {
  const colsOf = () => screen.getByRole('grid').style.getPropertyValue('--as-cols');
  const handleOf = (header: string) =>
    screen
      .getByRole('columnheader', { name: new RegExp(header, 'i') })
      .querySelector(':scope > span[aria-hidden="true"]:last-child') as HTMLElement;

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    document.dir = '';
  });

  function mount(over: Partial<Parameters<typeof VirtualTable<Q>>[0]> = {}) {
    // jsdom has no layout and no pointer capture: the header is 100px wide.
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 100 } as DOMRect);
    Object.assign(HTMLElement.prototype, { setPointerCapture: vi.fn() });
    return renderWithProviders(
      <VirtualTable label="Queues" storageKey="q" columns={columns} data={rows} rowKey={(r) => r.name} {...over} />,
    );
  }

  it('drags a column border to a new width, remembering it when released', () => {
    mount();
    const handle = handleOf('queue');
    fireEvent.pointerDown(handle, { button: 0, clientX: 100, pointerId: 1 });
    expect(handle).toHaveAttribute('data-active', 'true');
    fireEvent.pointerMove(handle, { clientX: 160, pointerId: 1 });
    expect(colsOf()).toContain('160px');
    fireEvent.pointerUp(handle, { pointerId: 1 });

    expect(handle).not.toHaveAttribute('data-active');
    expect(JSON.parse(localStorage.getItem('as.grid.q')!)).toEqual({ name: 160 });
    fireEvent.pointerMove(handle, { clientX: 300, pointerId: 1 });
    expect(colsOf()).toContain('160px');
  });

  it('never drags a column below its minimum, and stops on cancel', () => {
    mount();
    const handle = handleOf('queue');
    fireEvent.pointerDown(handle, { button: 0, clientX: 100, pointerId: 1 });
    fireEvent.pointerMove(handle, { clientX: -500, pointerId: 1 });
    expect(colsOf()).toMatch(/^48px /);
    fireEvent.pointerCancel(handle, { pointerId: 1 });
    expect(handle).not.toHaveAttribute('data-active');
  });

  it('ignores a press that is not the primary button', () => {
    mount();
    const handle = handleOf('queue');
    fireEvent.pointerDown(handle, { button: 2, clientX: 100, pointerId: 1 });
    expect(handle).not.toHaveAttribute('data-active');
  });

  it('drags the other way in a right-to-left document', () => {
    document.dir = 'rtl';
    mount();
    const handle = handleOf('queue');
    fireEvent.pointerDown(handle, { button: 0, clientX: 100, pointerId: 1 });
    fireEvent.pointerMove(handle, { clientX: 140, pointerId: 1 });
    expect(colsOf()).toContain('60px');
  });

  it('fits a column to its content on a double click, without sorting or bubbling', () => {
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return (this.textContent ?? '').length * 10;
    });
    const onSortChange = vi.fn();
    mount({ onSortChange });
    const handle = handleOf('queue');

    fireEvent.click(handle);
    expect(onSortChange).not.toHaveBeenCalled();
    fireEvent.doubleClick(handle);
    // SHIPMENTS is nine characters at ten pixels: 90, and two more for the border.
    expect(JSON.parse(localStorage.getItem('as.grid.q')!)).toEqual({ name: 92 });
  });

  it('shrinks with the right arrow in a right-to-left document', () => {
    document.dir = 'rtl';
    mount();
    const header = screen.getByRole('columnheader', { name: /queue/i });
    fireEvent.keyDown(header, { key: 'ArrowRight', ctrlKey: true, shiftKey: true });
    expect(colsOf()).toContain('84px');
  });
});

describe('VirtualTable: menus, focus and scrolling', () => {
  const cellOf = (text: string) => screen.getByText(text).closest('[role="gridcell"]') as HTMLElement;
  const scroller = () => screen.getByRole('grid').parentElement as HTMLElement;

  afterEach(() => {
    vi.restoreAllMocks();
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
  });

  it('says so when the browser refuses the clipboard', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    renderWithProviders(<VirtualTable label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    cellOf('ORDERS').focus();
    await user.keyboard('{Control>}c{/Control}');

    expect(await screen.findByText('Copy failed: the browser refused access to the clipboard.')).toBeInTheDocument();
  });

  it('leaves the browser’s copy alone when there is no clipboard to write to', async () => {
    renderWithProviders(<VirtualTable label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    cellOf('ORDERS').focus();
    const notPrevented = fireEvent.keyDown(cellOf('ORDERS'), { key: 'c', ctrlKey: true });

    expect(notPrevented).toBe(true);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('closes the revealed value on Escape and on scroll', async () => {
    renderWithProviders(<VirtualTable label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    const cell = cellOf('SHIPMENTS');
    Object.defineProperty(cell, 'scrollWidth', { configurable: true, value: 800 });
    Object.defineProperty(cell, 'clientWidth', { configurable: true, value: 120 });

    fireEvent.focus(cell);
    expect(screen.getByRole('dialog', { name: /full value/i })).toBeInTheDocument();
    fireEvent.keyDown(cell, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: /full value/i })).not.toBeInTheDocument();

    fireEvent.focus(cell);
    expect(screen.getByRole('dialog', { name: /full value/i })).toBeInTheDocument();
    fireEvent.scroll(scroller());
    expect(screen.queryByRole('dialog', { name: /full value/i })).not.toBeInTheDocument();
  });

  it('reports when the grid’s own scroll leaves and returns to the top', () => {
    const onAtTopChange = vi.fn();
    renderWithProviders(
      <VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} onAtTopChange={onAtTopChange} />,
    );
    fireEvent.scroll(scroller(), { target: { scrollTop: 120 } });
    expect(onAtTopChange).toHaveBeenLastCalledWith(false);
    fireEvent.scroll(scroller(), { target: { scrollTop: 3 } });
    expect(onAtTopChange).toHaveBeenLastCalledWith(true);
  });

  it('closes an open row menu when the grid scrolls', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <VirtualTable
        label="Queues"
        columns={columns}
        data={rows}
        rowKey={(r) => r.name}
        rowMenu={{ label: (r) => r.name, render: (r) => <Menu.Item>Open {r.name}</Menu.Item> }}
      />,
    );
    await user.click(screen.getByRole('button', { name: 'Actions for DLQ' }));
    expect(await screen.findByRole('menu', { name: 'Actions for DLQ' })).toBeInTheDocument();

    fireEvent.scroll(scroller());
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
  });

  it('swallows the browser’s echo of a context-menu key the grid already handled', async () => {
    renderWithProviders(
      <VirtualTable
        label="Queues"
        columns={columns}
        data={rows}
        rowKey={(r) => r.name}
        rowMenu={{ label: (r) => r.name, render: (r) => <Menu.Item>Open {r.name}</Menu.Item> }}
      />,
    );
    const cell = cellOf('ORDERS');
    cell.focus();
    fireEvent.keyDown(cell, { key: 'ContextMenu' });
    expect(await screen.findByRole('menu', { name: 'Actions for ORDERS' })).toBeInTheDocument();

    const notPrevented = fireEvent.contextMenu(cell, { clientX: 5, clientY: 5 });
    expect(notPrevented).toBe(false);
  });

  it('leaves the browser menu alone in a grid without one, and on a link or a selection', async () => {
    const linked: GridColumn<Q>[] = [
      { id: 'name', header: 'Queue', accessor: (r) => r.name, cell: (r) => <a href="#x">{r.name}</a> },
    ];
    const { unmount } = renderWithProviders(<VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} />);
    expect(fireEvent.contextMenu(cellOf('ORDERS'))).toBe(true);
    unmount();

    renderWithProviders(
      <VirtualTable
        columns={linked}
        data={rows}
        rowKey={(r) => r.name}
        rowMenu={{ label: (r) => r.name, render: (r) => <Menu.Item>Open {r.name}</Menu.Item> }}
      />,
    );
    expect(fireEvent.contextMenu(screen.getByRole('link', { name: 'ORDERS' }))).toBe(true);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    vi.spyOn(window, 'getSelection').mockReturnValue({ toString: () => 'some text' } as Selection);
    expect(fireEvent.contextMenu(cellOf('SHIPMENTS'))).toBe(true);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('hands focus to the neighbour that took the place of a row deleted from its menu', async () => {
    const user = userEvent.setup();
    let restore: (() => void) | undefined;

    function Deleting() {
      const [data, setData] = useState(rows);
      return (
        <VirtualTable
          label="Queues"
          columns={columns}
          data={data}
          rowKey={(r) => r.name}
          rowMenu={{
            label: (r) => r.name,
            render: (r, ctx) => (
              <Menu.Item
                onClick={() => {
                  restore = ctx.restoreFocus;
                  ctx.close();
                  setData((d) => d.filter((x) => x.name !== r.name));
                }}
              >
                Delete {r.name}
              </Menu.Item>
            ),
          }}
        />
      );
    }

    renderWithProviders(<Deleting />);
    await user.click(screen.getByRole('button', { name: 'Actions for SHIPMENTS' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Delete SHIPMENTS' }));
    await waitFor(() => expect(screen.queryByText('SHIPMENTS')).not.toBeInTheDocument());

    // A dialog opened from the menu calls this when it closes, after the row is gone.
    restore?.();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Actions for DLQ' })).toHaveFocus());
  });
});

describe('VirtualTable: remaining interactions', () => {
  const cellOf = (text: string) => screen.getByText(text).closest('[role="gridcell"]') as HTMLElement;

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
  });

  it('starts with no stored width when the stored value is not an object', () => {
    localStorage.setItem('as.grid.q', '5');
    renderWithProviders(
      <VirtualTable label="Queues" storageKey="q" columns={columns} data={rows} rowKey={(r) => r.name} />,
    );
    expect(screen.getByRole('grid').style.getPropertyValue('--as-cols')).not.toMatch(/(^|\s)\d+px/);
  });

  it('keeps a resized width for the visit when there is no storage key', () => {
    renderWithProviders(<VirtualTable label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    const header = screen.getByRole('columnheader', { name: /queue/i });
    fireEvent.keyDown(header, { key: 'ArrowRight', ctrlKey: true, shiftKey: true });

    expect(screen.getByRole('grid').style.getPropertyValue('--as-cols')).toMatch(/^\d+px /);
    expect(localStorage).toHaveLength(0);
  });

  it('copies with Cmd+C as well as Ctrl+C, but not with Alt held', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    renderWithProviders(<VirtualTable label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    const cell = cellOf('ORDERS');
    cell.focus();

    fireEvent.keyDown(cell, { key: 'c', ctrlKey: true, altKey: true });
    expect(writeText).not.toHaveBeenCalled();
    fireEvent.keyDown(cell, { key: 'C', metaKey: true });
    expect(writeText).toHaveBeenCalledWith('ORDERS');
    expect(await screen.findByText('Copied ORDERS')).toBeInTheDocument();
  });

  it('does not move the focus for an Alt+arrow, which belongs to the browser', () => {
    renderWithProviders(<VirtualTable label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    const cell = cellOf('ORDERS');
    cell.focus();
    const notPrevented = fireEvent.keyDown(cell, { key: 'ArrowDown', altKey: true });

    expect(notPrevented).toBe(true);
    expect(cell).toHaveFocus();
  });

  it('says a value was copied from the reveal panel', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    renderWithProviders(<VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} />);
    markClipped(cellOf('SHIPMENTS'));
    fireEvent.focus(cellOf('SHIPMENTS'));

    await user.click(
      within(screen.getByRole('dialog', { name: /full value/i })).getByRole('button', { name: /copy/i }),
    );
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();
  });

  it('draws a selectable grid with no selection given as unselected', () => {
    renderWithProviders(
      <VirtualTable columns={columns} data={rows} rowKey={(r) => r.name} selectable onToggleRow={vi.fn()} />,
    );
    expect(screen.getByRole('checkbox', { name: 'Select all on this page' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Select row ORDERS' })).not.toBeChecked();
    expect(screen.getAllByRole('row').filter((r) => r.getAttribute('aria-selected') === 'false')).toHaveLength(3);
  });

  it('closes a row menu from its own control, and leaves the row click to the row', async () => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    renderWithProviders(
      <VirtualTable
        label="Queues"
        columns={columns}
        data={rows}
        rowKey={(r) => r.name}
        onRowClick={onRowClick}
        rowMenu={{ label: (r) => r.name, render: (r) => <Menu.Item>Open {r.name}</Menu.Item> }}
      />,
    );
    const trigger = screen.getByRole('button', { name: 'Actions for ORDERS' });
    await user.click(trigger);
    expect(await screen.findByRole('menu', { name: 'Actions for ORDERS' })).toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'true');

    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
    // The control acts for itself: it never activates the row.
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('does not activate a row from a control inside it', async () => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    const withButton: GridColumn<Q>[] = [
      {
        id: 'name',
        header: 'Queue',
        accessor: (r) => r.name,
        cell: (r) => <button type="button">Open {r.name}</button>,
      },
    ];
    renderWithProviders(
      <VirtualTable columns={withButton} data={rows} rowKey={(r) => r.name} onRowClick={onRowClick} />,
    );

    await user.click(screen.getByRole('button', { name: 'Open ORDERS' }));
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('puts focus on the header when every row is gone by the time a menu returns it', async () => {
    const user = userEvent.setup();
    let restore: (() => void) | undefined;
    function Emptying() {
      const [data, setData] = useState<Q[]>([rows[0]]);
      return (
        <VirtualTable
          label="Queues"
          columns={columns}
          data={data}
          rowKey={(r) => r.name}
          onSortChange={vi.fn()}
          rowMenu={{
            label: (r) => r.name,
            render: (_r, ctx) => (
              <Menu.Item
                onClick={() => {
                  restore = ctx.restoreFocus;
                  ctx.close();
                  setData([]);
                }}
              >
                Delete
              </Menu.Item>
            ),
          }}
        />
      );
    }
    renderWithProviders(<Emptying />);
    await user.click(screen.getByRole('button', { name: 'Actions for ORDERS' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Delete' }));
    await waitFor(() => expect(screen.queryByText('ORDERS')).not.toBeInTheDocument());

    restore?.();
    // The header row's cell in the column the menu came from.
    await waitFor(() => expect(screen.getByRole('columnheader', { name: 'Actions' })).toHaveFocus());
  });
});
