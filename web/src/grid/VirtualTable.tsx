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
  width?: number;
}

const ROW_HEIGHT = 34;

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
      <Table stickyHeader className={styles.grid} role="grid">
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
                  style={c.width ? { width: c.width } : undefined}
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
                {row.getAllCells().map((cell, i) => (
                  <Table.Td
                    key={cell.id}
                    data-numeric={columns[i]?.numeric || undefined}
                    className={columns[i]?.numeric ? styles.num : undefined}
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
