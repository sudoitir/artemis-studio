import { useRef } from 'react';
import { Checkbox, Table } from '@mantine/core';
import { flexRender, tableFeatures, useTable, type ColumnDef } from '@tanstack/react-table';
import { useVirtualizer } from '@tanstack/react-virtual';

import styles from './VirtualTable.module.css';

/** No client-side row features — sorting / filtering / paging are all server-side (URL params). */
const features = tableFeatures({});
type Features = typeof features;
/** TanStack's row constraint is `Record<string, any> | any[]`; every view row is an object. */
type Row = Record<string, unknown>;

export interface GridColumn<T> {
  id: string;
  header: string;
  accessor: (row: T) => unknown;
  cell?: (row: T) => React.ReactNode;
  numeric?: boolean;
  /** The `sort` query value this column sorts by, if sortable. */
  sortKey?: string;
  width?: number;
}

const ROW_HEIGHT = 34;
const DEFAULT_WIDTH = 160;

interface VirtualTableProps<T> {
  columns: GridColumn<T>[];
  data: T[];
  sort?: string;
  onSortChange?: (sort: string | undefined) => void;
  onRowClick?: (row: T) => void;
  rowKey: (row: T) => string;
  emptyLabel?: React.ReactNode;
  /** Opt-in leading checkbox column. Selection state is owned by the caller (ephemeral React state). */
  selectable?: boolean;
  selected?: ReadonlySet<string>;
  onToggleRow?: (key: string) => void;
  /** Header select-all across the loaded page. `allSelected` is the current state; the caller flips it. */
  onToggleAll?: (keys: string[], allSelected: boolean) => void;
}

const SELECT_COL_WIDTH = 40;

/**
 * A virtualized data grid: TanStack Table v9 headless model rendered through
 * Mantine `Table`, row-virtualized with `@tanstack/react-virtual`. Smooth at a
 * few thousand rows. Sorting is a URL round-trip, not local state — the header
 * carries `aria-sort` from the current `sort` param and clicking it navigates.
 */
export function VirtualTable<T>({
  columns,
  data,
  sort,
  onSortChange,
  onRowClick,
  rowKey,
  emptyLabel,
  selectable,
  selected,
  onToggleRow,
  onToggleAll,
}: VirtualTableProps<T>) {
  const columnDefs: ColumnDef<Features, Row>[] = columns.map((c) => ({
    id: c.id,
    accessorFn: (row: Row) => c.accessor(row as T),
    header: c.header,
    cell: (ctx) =>
      c.cell ? c.cell(ctx.row.original as T) : String(ctx.getValue() ?? ''),
  }));

  const table = useTable<Features, Row>({
    features,
    columns: columnDefs,
    data: data as Row[],
  });
  const rows = table.getRowModel().rows;

  const scrollRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 14,
  });

  /**
   * Every virtualized row is its own `display: table` box, so it cannot inherit
   * the header's column widths — both sides have to be told the same thing.
   * Declared widths become weights against a default so the two fixed layouts
   * resolve identically.
   */
  const totalWeight = columns.reduce((sum, c) => sum + (c.width ?? DEFAULT_WIDTH), 0);
  const widthOf = (c: GridColumn<T>) =>
    `${(((c.width ?? DEFAULT_WIDTH) / totalWeight) * 100).toFixed(4)}%`;

  const sortField = sort?.replace(/^-/, '');
  const sortDesc = sort?.startsWith('-');

  const nextSort = (key: string): string | undefined => {
    if (sortField !== key) return key;
    if (!sortDesc) return `-${key}`;
    return undefined;
  };

  if (rows.length === 0 && emptyLabel) {
    return <div className={styles.empty}>{emptyLabel}</div>;
  }

  const loadedKeys = rows.map((r) => rowKey(r.original as T));
  const selectedCount = selected ? loadedKeys.filter((k) => selected.has(k)).length : 0;
  const allSelected = loadedKeys.length > 0 && selectedCount === loadedKeys.length;

  return (
    <div ref={scrollRef} className={styles.scroll}>
      <Table stickyHeader className={styles.grid} role="grid">
        <Table.Thead>
          <Table.Tr>
            {selectable ? (
              <Table.Th style={{ width: SELECT_COL_WIDTH }}>
                <Checkbox
                  size="xs"
                  aria-label={allSelected ? 'Deselect all on this page' : 'Select all on this page'}
                  checked={allSelected}
                  indeterminate={selectedCount > 0 && !allSelected}
                  onChange={() => onToggleAll?.(loadedKeys, allSelected)}
                />
              </Table.Th>
            ) : null}
            {columns.map((c) => {
              const sortable = Boolean(c.sortKey && onSortChange);
              const ariaSort = !sortable
                ? undefined
                : sortField === c.sortKey
                  ? sortDesc
                    ? 'descending'
                    : 'ascending'
                  : 'none';
              return (
                <Table.Th
                  key={c.id}
                  aria-sort={ariaSort}
                  data-numeric={c.numeric || undefined}
                  style={{ width: widthOf(c) }}
                >
                  {sortable ? (
                    <button
                      type="button"
                      className={styles.sortButton}
                      onClick={() => onSortChange?.(nextSort(c.sortKey!))}
                    >
                      {c.header}
                      <span aria-hidden="true">
                        {sortField === c.sortKey ? (sortDesc ? ' ▾' : ' ▴') : ''}
                      </span>
                    </button>
                  ) : (
                    c.header
                  )}
                </Table.Th>
              );
            })}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody style={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
          {virtualizer.getVirtualItems().map((vi) => {
            const row = rows[vi.index];
            const original = row.original as T;
            const key = rowKey(original);
            return (
              <Table.Tr
                key={key}
                className={onRowClick ? styles.clickable : undefined}
                data-selected={selected?.has(key) || undefined}
                onClick={onRowClick ? () => onRowClick(original) : undefined}
                style={{
                  position: 'absolute',
                  transform: `translateY(${vi.start}px)`,
                  width: '100%',
                  display: 'table',
                  tableLayout: 'fixed',
                }}
              >
                {selectable ? (
                  <Table.Td style={{ width: SELECT_COL_WIDTH }} onClick={(e) => e.stopPropagation()}>
                    <Checkbox
                      size="xs"
                      aria-label={`Select row ${key}`}
                      checked={selected?.has(key) ?? false}
                      onChange={() => onToggleRow?.(key)}
                    />
                  </Table.Td>
                ) : null}
                {row.getAllCells().map((cell, i) => (
                  <Table.Td
                    key={cell.id}
                    data-numeric={columns[i]?.numeric || undefined}
                    className={columns[i]?.numeric ? styles.num : undefined}
                    style={columns[i] ? { width: widthOf(columns[i]) } : undefined}
                  >
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </Table.Td>
                ))}
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </div>
  );
}
