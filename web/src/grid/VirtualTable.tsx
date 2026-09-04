import { useRef } from "react";
import { tableFeatures, useTable, type ColumnDef } from "@tanstack/react-table";
import { useVirtualizer } from "@tanstack/react-virtual";

import styles from "./VirtualTable.module.css";

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
  /**
   * Fixed track width in px, for a column whose values have a known shape — a
   * count, a routing type, a yes/no. Omit it for a column carrying free text
   * (an address, a queue name, a client id): those share whatever space the
   * fixed columns leave over, which is where a wide window is worth having.
   */
  width?: number;
}

const ROW_HEIGHT = 36;
/** How narrow a free-text column may get before the grid scrolls instead. */
const FLEX_MIN_WIDTH = 180;

/** The hover title for a cell, when its value is something a tooltip can say. */
function plainText(value: unknown): string | undefined {
  if (typeof value === "string") return value || undefined;
  if (typeof value === "number" || typeof value === "bigint")
    return String(value);
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
 * A virtualized data grid: one CSS grid track list, declared once and shared by
 * the header row and every body row, row-virtualized with
 * `@tanstack/react-virtual`. Smooth at a few thousand rows.
 *
 * <p>Columns are either fixed or free. A fixed column gets exactly its declared
 * px; a free column gets `minmax(floor, 1fr)`, so every pixel the window has
 * over the fixed columns' needs goes to the values that actually vary in length.
 * Below the floors the grid stops shrinking and its own container scrolls — the
 * page never does.
 *
 * <p>Sorting is a URL round-trip, not local state: the header carries
 * `aria-sort` from the current `sort` param and clicking it navigates.
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
   * The single source of truth for column geometry. Header and body rows are
   * both grid containers over this one track list, so they cannot drift apart
   * the way two independently laid-out tables can. The floors add up to the
   * grid's `min-inline-size`, which is also the width body rows resolve
   * against — without it the tracks would overflow a grid box still pinned to
   * the viewport, and the rows would be laid out narrower than the header.
   */
  const template = columns
    .map((c) => (c.width ? `${c.width}px` : `minmax(${FLEX_MIN_WIDTH}px, 1fr)`))
    .join(" ");
  const minInline = columns.reduce(
    (sum, c) => sum + (c.width ?? FLEX_MIN_WIDTH),
    0,
  );

  const sortField = sort?.replace(/^-/, "");
  const sortDesc = sort?.startsWith("-");

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
      <div
        className={styles.grid}
        role="grid"
        aria-rowcount={rows.length + 1}
        style={
          {
            "--as-cols": template,
            "--as-min-inline": `${minInline}px`,
            "--as-row-h": `${ROW_HEIGHT}px`,
          } as React.CSSProperties
        }
      >
        {/* Directly under the grid, not wrapped in a `rowgroup`: a sticky element
            can only travel inside its containing block, and a wrapper sized to the
            header itself would leave it nothing to travel through. */}
        <div
          className={`${styles.row} ${styles.headRow}`}
          role="row"
          aria-rowindex={1}
        >
          {columns.map((c) => {
            const sortable = Boolean(c.sortKey && onSortChange);
            const ariaSort = !sortable
              ? undefined
              : sortField === c.sortKey
                ? sortDesc
                  ? "descending"
                  : "ascending"
                : "none";
            return (
              <div
                key={c.id}
                role="columnheader"
                aria-sort={ariaSort}
                data-numeric={c.numeric || undefined}
                className={`${styles.cell} ${styles.headCell}`}
              >
                {sortable ? (
                  <button
                    type="button"
                    className={styles.sortButton}
                    onClick={() => onSortChange?.(nextSort(c.sortKey!))}
                  >
                    {c.header}
                    <span aria-hidden="true">
                      {sortField === c.sortKey ? (sortDesc ? " ▾" : " ▴") : ""}
                    </span>
                  </button>
                ) : (
                  c.header
                )}
              </div>
            );
          })}
        </div>

        <div
          className={styles.body}
          role="rowgroup"
          style={{ height: virtualizer.getTotalSize() }}
        >
          {virtualizer.getVirtualItems().map((vi) => {
            const original = rows[vi.index].original as T;
            return (
              <div
                key={rowKey(original)}
                role="row"
                aria-rowindex={vi.index + 2}
                className={`${styles.row} ${styles.bodyRow} ${onRowClick ? styles.clickable : ""}`}
                onClick={onRowClick ? () => onRowClick(original) : undefined}
                style={{ transform: `translateY(${vi.start}px)` }}
              >
                {columns.map((c) => {
                  const value = c.accessor(original);
                  return (
                    <div
                      key={c.id}
                      role="gridcell"
                      data-numeric={c.numeric || undefined}
                      className={`${styles.cell} ${c.numeric ? styles.num : ""}`}
                      // An ellipsized cell still has to be readable in full.
                      title={plainText(value)}
                    >
                      {c.cell ? c.cell(original) : String(value ?? "")}
                    </div>
                  );
                })}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
