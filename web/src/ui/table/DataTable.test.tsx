import { useState, type ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { Menu } from '@mantine/core';
import userEvent from '@testing-library/user-event';

import { renderWithProviders } from '../../test/render.tsx';
import { DataTable, type GridVariantProps } from './DataTable.tsx';
import type { Column } from './columns.ts';
import { fakeLayout } from './fakeLayout.ts';

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

const columns: Column<Q>[] = [
  { id: 'name', header: 'Queue', accessor: (r) => r.name, kind: 'text', priority: 'essential', sortKey: 'name' },
  { id: 'depth', header: 'Depth', accessor: (r) => r.depth, kind: 'number', priority: 'high', sortKey: 'depth' },
];

/** `DataTable` with the props every test would otherwise repeat. */
function Grid<T>(props: Omit<GridVariantProps<T>, 'label' | 'empty'> & { label?: string; empty?: ReactNode }) {
  return <DataTable<T> label="Table" empty={<p>Nothing to show</p>} {...props} />;
}

const cellSelector = '[role="gridcell"], [role="rowheader"]';

/** What a table stores for a viewer who set these widths. */
const state = (widths: Record<string, unknown>) => ({ v: 1, widths, hidden: [], shown: [], order: [] });
const stored = (widths: Record<string, unknown>) => JSON.stringify(state(widths));

describe('DataTable', () => {
  it('renders a row per datum', () => {
    renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} />);
    expect(screen.getByText('ORDERS')).toBeInTheDocument();
    expect(screen.getByText('SHIPMENTS')).toBeInTheDocument();
    expect(screen.getByText('DLQ')).toBeInTheDocument();
  });

  it('shows the empty label when there is no data', () => {
    renderWithProviders(<Grid columns={columns} data={[]} rowKey={(r) => r.name} empty={<p>No queues match</p>} />);
    expect(screen.getByText('No queues match')).toBeInTheDocument();
  });

  it('flips aria-sort as the sort prop cycles, and reports the next value on click', async () => {
    const user = userEvent.setup();
    const seen: (string | undefined)[] = [];

    function Harness() {
      const [sort, setSort] = useState<string | undefined>(undefined);
      return (
        <Grid
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
          <Grid
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

    const cellOf = (text: string) => screen.getByText(text).closest(cellSelector) as HTMLElement;
    /** From the button before the table, past its Columns control, into the grid. */
    async function enterGrid(user: ReturnType<typeof userEvent.setup>) {
      await user.click(screen.getByRole('button', { name: 'before' }));
      await user.tab();
      await user.tab();
    }

    it('is one tab stop, named, entering on the first row', async () => {
      const user = userEvent.setup();
      renderWithProviders(<Harness />);
      expect(screen.getByRole('grid', { name: 'Queues' })).toBeInTheDocument();

      screen.getByRole('button', { name: 'before' }).focus();
      await user.tab();
      expect(screen.getByRole('button', { name: 'Columns' })).toHaveFocus();
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
      await enterGrid(user);
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
      await enterGrid(user);
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
      await enterGrid(user);
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
      await enterGrid(user);
      await user.keyboard('{ArrowDown}');
      expect(cellOf('SHIPMENTS')).toHaveFocus();
      rerender(<Harness data={[...rows].reverse()} />);
      expect(cellOf('SHIPMENTS')).toHaveFocus();
    });

    it('hands focus to a neighbour when the focused row goes away', async () => {
      const user = userEvent.setup();
      const { rerender } = renderWithProviders(<Harness />);
      await enterGrid(user);
      await user.keyboard('{ArrowDown}');
      rerender(<Harness data={rows.filter((r) => r.name !== 'SHIPMENTS')} />);
      await waitFor(() => expect(cellOf('DLQ')).toHaveFocus());
    });

    it('copies the focused cell with Ctrl+C and says so', async () => {
      const user = userEvent.setup();
      const writeText = vi.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
      renderWithProviders(<Harness />);
      await enterGrid(user);
      await user.keyboard('{Control>}c{/Control}');
      expect(writeText).toHaveBeenCalledWith('ORDERS');
      expect(await screen.findByText('Copied ORDERS')).toBeInTheDocument();
    });
  });

  describe('row menu (ADR-0107)', () => {
    function WithMenu({ onDelete = () => {} }: { onDelete?: (name: string) => void }) {
      return (
        <Grid
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
      return screen.getByText(text).closest(cellSelector) as HTMLElement;
    }
  });

  it('reveals the full value with a copy control when a cell is actually clipped', async () => {
    renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} />);
    const cell = screen.getByText('SHIPMENTS').closest(cellSelector) as HTMLElement;

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

  it('reveals a clipped value to a resting pointer, and swaps it at once to the next clipped cell', async () => {
    renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} />);
    const first = screen.getByText('SHIPMENTS').closest(cellSelector) as HTMLElement;
    const second = screen.getByText('DLQ').closest(cellSelector) as HTMLElement;
    markClipped(first);
    markClipped(second);

    fireEvent.pointerEnter(first);
    expect(screen.queryByRole('dialog', { name: /full value/i })).not.toBeInTheDocument();
    const panel = await screen.findByRole('dialog', { name: /full value/i });
    expect(within(panel).getByText('SHIPMENTS')).toBeInTheDocument();

    fireEvent.pointerLeave(first);
    fireEvent.pointerEnter(second);
    expect(within(screen.getByRole('dialog', { name: /full value/i })).getByText('DLQ')).toBeInTheDocument();
  });

  it('does not reveal a value the pointer only passed over', async () => {
    renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} />);
    const cell = screen.getByText('SHIPMENTS').closest(cellSelector) as HTMLElement;
    markClipped(cell);

    fireEvent.pointerEnter(cell);
    fireEvent.pointerLeave(cell);
    await new Promise((r) => setTimeout(r, 500));
    expect(screen.queryByRole('dialog', { name: /full value/i })).not.toBeInTheDocument();
  });

  it('calls onRowClick with the row', async () => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} onRowClick={onRowClick} />);
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
        <Grid
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
        <Grid
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

  describe('column widths (ADR-0161)', () => {
    afterEach(() => {
      localStorage.clear();
      vi.restoreAllMocks();
    });

    const colsOf = () => (screen.getByRole('grid').parentElement as HTMLElement).style.getPropertyValue('--as-cols');

    it('resizes a column from its header with Ctrl+Shift+Arrow, announces it and remembers it', async () => {
      localStorage.setItem('as.table.queues', stored({ name: 200 }));
      renderWithProviders(
        <Grid
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
      expect(JSON.parse(localStorage.getItem('as.table.queues')!)).toEqual(state({ name: 216 }));
      // Focus did not move: the grid is still one tab stop on the same header.
      expect(header).toHaveFocus();
    });

    it('describes the resize keys on every header', () => {
      renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
      for (const header of screen.getAllByRole('columnheader')) {
        expect(header.getAttribute('aria-description')).toMatch(/Ctrl\+Shift\+Left or Right/);
      }
    });

    it('ignores stored widths it cannot trust', () => {
      localStorage.setItem('as.table.queues', stored({ name: 'wide', gone: 300, depth: 1e9 }));
      renderWithProviders(
        <Grid label="Queues" storageKey="queues" columns={columns} data={rows} rowKey={(r) => r.name} />,
      );
      expect(colsOf()).toMatch(/^minmax\(/);

      localStorage.setItem('as.table.other', '{not json');
      renderWithProviders(
        <Grid label="Other" storageKey="other" columns={columns} data={rows} rowKey={(r) => r.name} />,
      );
      expect(screen.getByRole('grid', { name: 'Other' })).toBeInTheDocument();
    });
  });
});

describe('DataTable: cell values', () => {
  interface V {
    id: string;
    value: unknown;
  }
  const vcols: Column<V>[] = [
    { id: 'id', header: 'Id', accessor: (r) => r.id, kind: 'text', priority: 'essential' },
    { id: 'value', header: 'Value', accessor: (r) => r.value, kind: 'text', priority: 'high' },
  ];
  const cellOf = (text: string) => screen.getByText(text).closest(cellSelector) as HTMLElement;

  it('can reveal a cell’s text or number, and nothing for anything else, without a native title', () => {
    renderWithProviders(
      <Grid
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
    expect(valueCell('a')).toHaveAttribute('data-full', 'text');
    expect(valueCell('b')).toHaveAttribute('data-full', '42');
    expect(valueCell('c')).toHaveAttribute('data-full', '7');
    expect(valueCell('d')).not.toHaveAttribute('data-full');
    expect(valueCell('e')).not.toHaveAttribute('data-full');
    expect(valueCell('a')).not.toHaveAttribute('title');
    expect(valueCell('e')).toHaveTextContent('true');
    expect(valueCell('f')).toBeEmptyDOMElement();
  });

  it('draws what a column’s own cell renderer returns', () => {
    const custom: Column<V>[] = [
      {
        id: 'id',
        header: 'Id',
        accessor: (r) => r.id,
        kind: 'text',
        priority: 'essential',
        cell: (r) => <b>{r.id}!</b>,
      },
    ];
    renderWithProviders(<Grid columns={custom} data={[{ id: 'a', value: 1 }]} rowKey={(r) => r.id} />);
    expect(screen.getByText('a!').tagName).toBe('B');
  });
});

describe('DataTable: pointer resizing', () => {
  const colsOf = () => (screen.getByRole('grid').parentElement as HTMLElement).style.getPropertyValue('--as-cols');
  const handleOf = (header: string) =>
    screen
      .getByRole('columnheader', { name: new RegExp(header, 'i') })
      .querySelector(':scope > span[aria-hidden="true"]:last-child') as HTMLElement;

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    document.dir = '';
  });

  function mount(over: Partial<Parameters<typeof Grid<Q>>[0]> = {}) {
    // jsdom has no layout and no pointer capture: the header is 100px wide.
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 100 } as DOMRect);
    Object.assign(HTMLElement.prototype, { setPointerCapture: vi.fn() });
    return renderWithProviders(
      <Grid label="Queues" storageKey="q" columns={columns} data={rows} rowKey={(r) => r.name} {...over} />,
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
    expect(JSON.parse(localStorage.getItem('as.table.q')!)).toEqual(state({ name: 160 }));
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

  it('puts a column back to fitting on a double click, without sorting or bubbling', async () => {
    localStorage.setItem('as.table.q', stored({ name: 200 }));
    const onSortChange = vi.fn();
    mount({ onSortChange });
    const handle = handleOf('queue');
    expect(colsOf()).toContain('200px');

    fireEvent.click(handle);
    expect(onSortChange).not.toHaveBeenCalled();
    fireEvent.doubleClick(handle);

    expect(colsOf()).not.toContain('200px');
    expect(JSON.parse(localStorage.getItem('as.table.q')!)).toEqual(state({}));
    expect(await screen.findByText('Queue column fitted to its content')).toBeInTheDocument();
  });

  it('shrinks with the right arrow in a right-to-left document', () => {
    document.dir = 'rtl';
    mount();
    const header = screen.getByRole('columnheader', { name: /queue/i });
    fireEvent.keyDown(header, { key: 'ArrowRight', ctrlKey: true, shiftKey: true });
    expect(colsOf()).toContain('84px');
  });
});

describe('DataTable: menus, focus and scrolling', () => {
  const cellOf = (text: string) => screen.getByText(text).closest(cellSelector) as HTMLElement;
  const scroller = () => screen.getByRole('grid').parentElement as HTMLElement;

  afterEach(() => {
    vi.restoreAllMocks();
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
  });

  it('says so when the browser refuses the clipboard', async () => {
    const user = userEvent.setup();
    const writeText = vi.fn().mockRejectedValue(new Error('denied'));
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    cellOf('ORDERS').focus();
    await user.keyboard('{Control>}c{/Control}');

    expect(await screen.findByText('Copy failed: the browser refused access to the clipboard.')).toBeInTheDocument();
  });

  it('leaves the browser’s copy alone when there is no clipboard to write to', async () => {
    renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    cellOf('ORDERS').focus();
    const notPrevented = fireEvent.keyDown(cellOf('ORDERS'), { key: 'c', ctrlKey: true });

    expect(notPrevented).toBe(true);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('closes the revealed value on Escape and on scroll', async () => {
    renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
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
    renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} onAtTopChange={onAtTopChange} />);
    fireEvent.scroll(scroller(), { target: { scrollTop: 120 } });
    expect(onAtTopChange).toHaveBeenLastCalledWith(false);
    fireEvent.scroll(scroller(), { target: { scrollTop: 3 } });
    expect(onAtTopChange).toHaveBeenLastCalledWith(true);
  });

  it('closes an open row menu when the grid scrolls', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Grid
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
      <Grid
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
    const linked: Column<Q>[] = [
      {
        id: 'name',
        header: 'Queue',
        accessor: (r) => r.name,
        kind: 'text',
        priority: 'essential',
        cell: (r) => <a href="#x">{r.name}</a>,
      },
    ];
    const { unmount } = renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} />);
    expect(fireEvent.contextMenu(cellOf('ORDERS'))).toBe(true);
    unmount();

    renderWithProviders(
      <Grid
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
        <Grid
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

describe('DataTable: remaining interactions', () => {
  const cellOf = (text: string) => screen.getByText(text).closest(cellSelector) as HTMLElement;

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined });
  });

  it('starts with no stored width when the stored value is not an object', () => {
    localStorage.setItem('as.table.q', '5');
    renderWithProviders(<Grid label="Queues" storageKey="q" columns={columns} data={rows} rowKey={(r) => r.name} />);
    expect((screen.getByRole('grid').parentElement as HTMLElement).style.getPropertyValue('--as-cols')).toMatch(
      /^minmax\(/,
    );
  });

  it('keeps a resized width for the visit when there is no storage key', () => {
    renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    const header = screen.getByRole('columnheader', { name: /queue/i });
    fireEvent.keyDown(header, { key: 'ArrowRight', ctrlKey: true, shiftKey: true });

    expect((screen.getByRole('grid').parentElement as HTMLElement).style.getPropertyValue('--as-cols')).toMatch(
      /^\d+px /,
    );
    expect(Object.keys(localStorage).filter((key) => key.startsWith('as.table'))).toEqual([]);
  });

  it('copies with Cmd+C as well as Ctrl+C, but not with Alt held', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    const cell = cellOf('ORDERS');
    cell.focus();

    fireEvent.keyDown(cell, { key: 'c', ctrlKey: true, altKey: true });
    expect(writeText).not.toHaveBeenCalled();
    fireEvent.keyDown(cell, { key: 'C', metaKey: true });
    expect(writeText).toHaveBeenCalledWith('ORDERS');
    expect(await screen.findByText('Copied ORDERS')).toBeInTheDocument();
  });

  it('announces the same words a second time', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
    const cell = cellOf('ORDERS');
    cell.focus();
    fireEvent.keyDown(cell, { key: 'c', ctrlKey: true });
    const status = await screen.findByText('Copied ORDERS');
    const region = status.closest('[role="status"]') ?? status;

    fireEvent.keyDown(cell, { key: 'c', ctrlKey: true });
    // The region is emptied first, so the repeat is a change a screen reader announces.
    await waitFor(() => expect(region).toHaveTextContent(''));
    await waitFor(() => expect(region).toHaveTextContent('Copied ORDERS'));
  });

  it('does not move the focus for an Alt+arrow, which belongs to the browser', () => {
    renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />);
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
    renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} />);
    markClipped(cellOf('SHIPMENTS'));
    fireEvent.focus(cellOf('SHIPMENTS'));

    await user.click(
      within(screen.getByRole('dialog', { name: /full value/i })).getByRole('button', { name: /copy/i }),
    );
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();
  });

  it('draws a selectable grid with no selection given as unselected', () => {
    renderWithProviders(<Grid columns={columns} data={rows} rowKey={(r) => r.name} selectable onToggleRow={vi.fn()} />);
    expect(screen.getByRole('checkbox', { name: 'Select all on this page' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Select row ORDERS' })).not.toBeChecked();
    expect(screen.getAllByRole('row').filter((r) => r.getAttribute('aria-selected') === 'false')).toHaveLength(3);
  });

  it('closes a row menu from its own control, and leaves the row click to the row', async () => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    renderWithProviders(
      <Grid
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
    const withButton: Column<Q>[] = [
      {
        id: 'name',
        header: 'Queue',
        accessor: (r) => r.name,
        kind: 'text',
        priority: 'essential',
        cell: (r) => <button type="button">Open {r.name}</button>,
      },
    ];
    renderWithProviders(<Grid columns={withButton} data={rows} rowKey={(r) => r.name} onRowClick={onRowClick} />);

    await user.click(screen.getByRole('button', { name: 'Open ORDERS' }));
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('puts focus on the header when every row is gone by the time a menu returns it', async () => {
    const user = userEvent.setup();
    let restore: (() => void) | undefined;
    function Emptying() {
      const [data, setData] = useState<Q[]>([rows[0]]);
      return (
        <Grid
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

describe('DataTable: frame and semantics', () => {
  it('sizes the grid for assistive technology over the columns it renders', () => {
    renderWithProviders(
      <Grid
        label="Queues"
        columns={columns}
        data={rows}
        rowKey={(r) => r.name}
        selectable
        rowMenu={{ label: (r) => r.name, render: () => null }}
      />,
    );
    const grid = screen.getByRole('grid', { name: 'Queues' });
    // The header row counts, so three rows of data make four.
    expect(grid).toHaveAttribute('aria-rowcount', '4');
    // Select, Queue, Depth and Actions.
    expect(grid).toHaveAttribute('aria-colcount', '4');
    expect(screen.getByRole('columnheader', { name: 'Actions' })).toHaveAttribute('aria-colindex', '4');
    expect(screen.getByRole('rowheader', { name: 'SHIPMENTS' })).toHaveAttribute('aria-colindex', '2');
    expect(screen.getByRole('gridcell', { name: '431' })).toHaveAttribute('aria-colindex', '3');
  });

  it('keeps the empty state beside the grid, never inside it', () => {
    renderWithProviders(
      <Grid label="Queues" columns={columns} data={[]} rowKey={(r) => r.name} empty={<p>Nothing here yet</p>} />,
    );
    const grid = screen.getByRole('grid', { name: 'Queues' });
    expect(screen.getByText('Nothing here yet')).toBeInTheDocument();
    expect(grid).not.toContainElement(screen.getByText('Nothing here yet'));
    expect(grid).toHaveAttribute('aria-rowcount', '1');
  });

  it('shows the error in place of the empty state, beside the grid', () => {
    renderWithProviders(
      <Grid
        label="Queues"
        columns={columns}
        data={[]}
        rowKey={(r) => r.name}
        empty={<p>Nothing here yet</p>}
        error={<p role="alert">Studio could not load the queues</p>}
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('could not load');
    expect(screen.queryByText('Nothing here yet')).not.toBeInTheDocument();
    expect(screen.getByRole('grid')).not.toContainElement(screen.getByRole('alert'));
  });

  it('shows the header and hidden placeholder rows while the first load runs', () => {
    renderWithProviders(
      <Grid
        label="Queues"
        columns={columns}
        data={[]}
        rowKey={(r) => r.name}
        loading
        empty={<p>Nothing here yet</p>}
      />,
    );
    const grid = screen.getByRole('grid', { name: 'Queues' });
    expect(grid).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading Queues')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: /queue/i })).toBeInTheDocument();
    expect(screen.queryByText('Nothing here yet')).not.toBeInTheDocument();
    // The placeholders are not rows of the grid.
    expect(screen.getAllByRole('row')).toHaveLength(1);
    expect(grid).toHaveAttribute('aria-rowcount', '1');
  });

  it('keeps its rows while it refetches', () => {
    renderWithProviders(<Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} loading />);
    expect(screen.getByText('ORDERS')).toBeInTheDocument();
    expect(screen.queryByText('Loading Queues')).not.toBeInTheDocument();
  });

  it('announces the sort once the sorted rows have landed', async () => {
    function Harness({ sort, data }: { sort?: string; data: Q[] }) {
      return (
        <Grid label="Queues" columns={columns} data={data} rowKey={(r) => r.name} sort={sort} onSortChange={vi.fn()} />
      );
    }
    const { rerender } = renderWithProviders(<Harness data={rows} />);
    rerender(<Harness sort="-depth" data={rows} />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    rerender(<Harness sort="-depth" data={[...rows].reverse()} />);
    expect(await screen.findByText('Sorted by Depth, descending')).toBeInTheDocument();
  });

  it('announces a sort whose rows land in the same render, as cached rows do', async () => {
    function Harness({ sort, data }: { sort?: string; data: Q[] }) {
      return (
        <Grid label="Queues" columns={columns} data={data} rowKey={(r) => r.name} sort={sort} onSortChange={vi.fn()} />
      );
    }
    const { rerender } = renderWithProviders(<Harness data={rows} />);
    rerender(<Harness sort="-depth" data={[...rows].reverse()} />);
    expect(await screen.findByText('Sorted by Depth, descending')).toBeInTheDocument();
  });

  it('keeps the active cell on the nearest column when its own goes away', async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(
      <Grid label="Queues" columns={columns} data={rows} rowKey={(r) => r.name} />,
    );
    await user.click(screen.getByText('12'));
    expect(screen.getByText('12').closest(cellSelector)).toHaveAttribute('tabindex', '0');

    rerender(<Grid label="Queues" columns={[columns[0]]} data={rows} rowKey={(r) => r.name} />);
    expect(screen.getByText('ORDERS').closest(cellSelector)).toHaveAttribute('tabindex', '0');
  });

  it('reads the stored state again when its storage key changes', () => {
    localStorage.setItem('as.table.queues', stored({ name: 200 }));
    localStorage.setItem('as.table.topics', stored({ name: 320 }));
    const grid = (key: string) => (
      <Grid label="Items" storageKey={key} columns={columns} data={rows} rowKey={(r) => r.name} />
    );
    const { rerender } = renderWithProviders(grid('queues'));
    const colsOf = () => (screen.getByRole('grid').parentElement as HTMLElement).style.getPropertyValue('--as-cols');
    expect(colsOf()).toContain('200px');

    rerender(grid('topics'));
    expect(colsOf()).toContain('320px');
    expect(colsOf()).not.toContain('200px');
  });

  it('puts the caller’s toolbar controls beside its Columns control', () => {
    renderWithProviders(
      <Grid
        label="Queues"
        columns={columns}
        data={rows}
        rowKey={(r) => r.name}
        toolbar={{ start: <button type="button">Filter</button> }}
      />,
    );
    expect(screen.getByRole('group', { name: 'Queues controls' })).toContainElement(
      screen.getByRole('button', { name: 'Filter' }),
    );
    expect(screen.getByRole('button', { name: 'Columns' })).toBeInTheDocument();
  });
});

describe('DataTable: columns control', () => {
  afterEach(() => localStorage.clear());

  it('hides and shows a column, counts it in its name and announces the change', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Grid label="Queues" storageKey="q" columns={columns} data={rows} rowKey={(r) => r.name} />);

    await user.click(screen.getByRole('button', { name: 'Columns' }));
    // The column that identifies a row cannot be hidden.
    expect(await screen.findByRole('checkbox', { name: 'Queue' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: 'Depth' }));

    expect(screen.queryByRole('columnheader', { name: /depth/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Columns,? ?1 hidden$/ })).toBeInTheDocument();
    expect(await screen.findByText('1 column hidden')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('as.table.q')!)).toMatchObject({ hidden: ['depth'] });

    await user.click(screen.getByRole('checkbox', { name: 'Depth' }));
    expect(screen.getByRole('columnheader', { name: /depth/i })).toBeInTheDocument();
    expect(await screen.findByText('No columns hidden')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('as.table.q')!)).toMatchObject({ hidden: [], shown: ['depth'] });
  });

  it('switches the density for every table and resets widths', async () => {
    const user = userEvent.setup();
    localStorage.setItem('as.table.q', stored({ name: 200 }));
    renderWithProviders(<Grid label="Queues" storageKey="q" columns={columns} data={rows} rowKey={(r) => r.name} />);

    await user.click(screen.getByRole('button', { name: 'Columns' }));
    await user.click(await screen.findByRole('radio', { name: 'Comfortable' }));
    expect(document.documentElement.dataset.density).toBe('comfortable');

    await user.click(screen.getByRole('button', { name: 'Reset widths' }));
    expect(JSON.parse(localStorage.getItem('as.table.q')!)).toEqual(state({}));
    expect(screen.getByRole('button', { name: 'Reset widths' })).toBeDisabled();
  });

  it('never hides the column that identifies a row, whatever storage says', () => {
    localStorage.setItem('as.table.q', JSON.stringify({ ...state({}), hidden: ['name', 'depth'] }));
    renderWithProviders(<Grid label="Queues" storageKey="q" columns={columns} data={rows} rowKey={(r) => r.name} />);
    expect(screen.getByRole('columnheader', { name: /queue/i })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: /depth/i })).not.toBeInTheDocument();
  });

  it('orders the columns as the viewer chose, keeping the first one first', () => {
    const three: Column<Q>[] = [
      ...columns,
      { id: 'extra', header: 'Extra', accessor: (r) => r.depth, kind: 'number', priority: 'high' },
    ];
    localStorage.setItem('as.table.q', JSON.stringify({ ...state({}), order: ['extra', 'name', 'depth'] }));
    renderWithProviders(<Grid label="Queues" storageKey="q" columns={three} data={rows} rowKey={(r) => r.name} />);
    const names = screen.getAllByRole('columnheader').map((header) => header.textContent);
    expect(names).toEqual(['Queue', 'Extra', 'Depth']);
  });

  describe('moving a column', () => {
    const three: Column<Q>[] = [
      ...columns,
      { id: 'extra', header: 'Extra', accessor: (r) => r.depth, kind: 'number', priority: 'high' },
    ];
    const grid = () => <Grid label="Queues" storageKey="q" columns={three} data={rows} rowKey={(r) => r.name} />;
    const headers = () => screen.getAllByRole('columnheader').map((header) => header.textContent);

    it('moves a column earlier and later, announces it, remembers it and keeps focus on the button', async () => {
      const user = userEvent.setup();
      renderWithProviders(grid());
      await user.click(screen.getByRole('button', { name: 'Columns' }));

      await user.click(await screen.findByRole('button', { name: 'Move Extra earlier' }));
      expect(headers()).toEqual(['Queue', 'Extra', 'Depth']);
      expect(await screen.findByText('Extra moved to position 2 of 3')).toBeInTheDocument();
      expect(JSON.parse(localStorage.getItem('as.table.q')!)).toMatchObject({ order: ['name', 'extra', 'depth'] });
      // At the bound the pressed button is disabled, so focus moves to its sibling.
      expect(screen.getByRole('button', { name: 'Move Extra earlier' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Move Extra later' })).toHaveFocus();

      await user.click(screen.getByRole('button', { name: 'Move Extra later' }));
      expect(headers()).toEqual(['Queue', 'Depth', 'Extra']);
      expect(screen.getByRole('button', { name: 'Move Extra later' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Move Extra earlier' })).toHaveFocus();
    });

    it('keeps focus on a button that can still move, and never moves the first column', async () => {
      const user = userEvent.setup();
      const four: Column<Q>[] = [
        ...three,
        { id: 'more', header: 'More', accessor: (r) => r.depth, kind: 'number', priority: 'high' },
      ];
      renderWithProviders(<Grid label="Queues" storageKey="q" columns={four} data={rows} rowKey={(r) => r.name} />);
      await user.click(screen.getByRole('button', { name: 'Columns' }));

      await user.click(await screen.findByRole('button', { name: 'Move More earlier' }));
      expect(screen.getByRole('button', { name: 'Move More earlier' })).toHaveFocus();
      expect(screen.getByRole('button', { name: 'Move Queue earlier' })).toBeDisabled();
      expect(screen.getByRole('button', { name: 'Move Queue later' })).toBeDisabled();
      // Nothing moves before the column that identifies a row.
      expect(screen.getByRole('button', { name: 'Move Depth earlier' })).toBeDisabled();
    });
  });
});

describe('DataTable: the solver in the grid', () => {
  let restore: () => void = () => {};
  afterEach(() => {
    restore();
    localStorage.clear();
  });

  const wide: Column<Q>[] = [
    ...columns,
    { id: 'consumers', header: 'Consumers', accessor: () => 3, kind: 'number', priority: 'low' },
  ];

  it('hides the least important columns when they do not fit, and says how many', () => {
    restore = fakeLayout(260);
    renderWithProviders(<Grid label="Queues" columns={wide} data={rows} rowKey={(r) => r.name} />);

    expect(screen.getByRole('columnheader', { name: /queue/i })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: /consumers/i })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /hidden/ })).toBeInTheDocument();
    expect(screen.getByRole('grid')).toHaveAttribute(
      'aria-colcount',
      String(screen.getAllByRole('columnheader').length),
    );
  });

  it('shows every column when they fit', () => {
    restore = fakeLayout(1200);
    renderWithProviders(<Grid label="Queues" columns={wide} data={rows} rowKey={(r) => r.name} />);
    expect(screen.getAllByRole('columnheader')).toHaveLength(3);
    expect(screen.getByRole('button', { name: 'Columns' })).toBeInTheDocument();
  });

  it('keeps a column the viewer chose to show, and scrolls instead', () => {
    restore = fakeLayout(260);
    localStorage.setItem('as.table.q', JSON.stringify({ ...state({}), shown: ['consumers', 'depth'] }));
    renderWithProviders(<Grid label="Queues" storageKey="q" columns={wide} data={rows} rowKey={(r) => r.name} />);
    expect(screen.getAllByRole('columnheader')).toHaveLength(3);
    expect(screen.getByRole('grid').parentElement).toHaveAttribute('data-overflow', 'true');
  });
});

describe('DataTable: rendering cost', () => {
  it('redraws only the row whose selection changed', async () => {
    const user = userEvent.setup();
    const draws: Record<string, number> = {};
    const counted: Column<Q>[] = [
      {
        id: 'name',
        header: 'Queue',
        accessor: (r) => r.name,
        kind: 'text',
        priority: 'essential',
        cell: (r) => {
          draws[r.name] = (draws[r.name] ?? 0) + 1;
          return r.name;
        },
      },
    ];
    function Harness() {
      const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
      return (
        <Grid
          label="Queues"
          columns={counted}
          data={rows}
          rowKey={(r) => r.name}
          selectable
          selected={selected}
          onToggleRow={(key) => setSelected(new Set([...selected, key]))}
        />
      );
    }
    renderWithProviders(<Harness />);
    const before = { ...draws };

    await user.click(screen.getByRole('checkbox', { name: 'Select row SHIPMENTS' }));

    expect(screen.getByRole('checkbox', { name: 'Select row SHIPMENTS' })).toBeChecked();
    expect(draws.SHIPMENTS).toBeGreaterThan(before.SHIPMENTS);
    expect(draws.ORDERS).toBe(before.ORDERS);
    expect(draws.DLQ).toBe(before.DLQ);
  });
});
