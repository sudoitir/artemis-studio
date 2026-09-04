import { useRef } from 'react';
import { Table } from '@mantine/core';
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
  /** Declared column width in px. Also its share of any space left over. */
  width?: number;
}

const ROW_HEIGHT = 34;
const DEFAULT_WIDTH = 160;

/** The hover title for a cell, when its value is something a tooltip can say. */
function plainText(value: unknown): string | undefined {
  if (typeof value === 'string') return value || undefined;
  if (typeof value === 'number' || typeof value === 'bigint') return String(value);
  return undefined;
}

interface VirtualTableProps<T> {
  columns: GridColumn<T>[];
  data: T[];
  sort?: string;
  onSortChange?: (sort: string | undefined) => void;
  onRowClick?: (row: T) => void;
  rowKey: (row: T) => string;
  emptyLabel?: React.ReactNode;
}

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
   * the header's column widths — both sides have to be told the same thing, in
   * the same unit. Declared px widths are the floor: below their sum the grid
   * stops shrinking and {@link styles.scroll} scrolls sideways instead of
   * squeezing columns down to clipped stumps. Above it, both fixed layouts
   * spread the slack the same way, so the header stays over its cells.
   */
  const widthOf = (c: GridColumn<T>) => c.width ?? DEFAULT_WIDTH;
  const minWidth = columns.reduce((sum, c) => sum + widthOf(c), 0);

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

  return (
    <div ref={scrollRef} className={styles.scroll}>
      <Table stickyHeader className={styles.grid} role="grid" style={{ minWidth }}>
        <Table.Thead>
          <Table.Tr>
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
            return (
              <Table.Tr
                key={rowKey(original)}
                className={onRowClick ? styles.clickable : undefined}
                onClick={onRowClick ? () => onRowClick(original) : undefined}
                style={{
                  position: 'absolute',
                  transform: `translateY(${vi.start}px)`,
                  width: '100%',
                  display: 'table',
                  tableLayout: 'fixed',
                }}
              >
                {row.getAllCells().map((cell, i) => {
                  const column = columns[i];
                  return (
                    <Table.Td
                      key={cell.id}
                      data-numeric={column?.numeric || undefined}
                      className={column?.numeric ? styles.num : undefined}
                      style={column ? { width: widthOf(column) } : undefined}
                      // An ellipsized cell still has to be readable in full.
                      title={column ? plainText(column.accessor(original)) : undefined}
                    >
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </Table.Td>
                  );
                })}
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </div>
  );
}
