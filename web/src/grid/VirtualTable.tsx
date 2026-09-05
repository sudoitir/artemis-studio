import { useCallback, useRef, useState } from "react";
import { Checkbox, CopyButton, Portal } from "@mantine/core";
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
/** The leading checkbox column's fixed track. */
const SELECT_COL_WIDTH = 40;

/** The hover title for a cell, when its value is something a tooltip can say. */
function plainText(value: unknown): string | undefined {
  if (typeof value === "string") return value || undefined;
  if (typeof value === "number" || typeof value === "bigint")
    return String(value);
  return undefined;
}

interface Reveal {
  text: string;
  /** Viewport rect of the cell the panel is anchored to. */
  rect: DOMRect;
}

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
 * `aria-sort` from the current `sort` param and clicking it navigates. Row
 * selection is opt-in (`selectable`) and its state lives with the caller.
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
  }));

  const table = useTable<Features, Row>({
    features,
    columns: columnDefs,
    data: data as Row[],
  });
  const rows = table.getRowModel().rows;

  const scrollRef = useRef<HTMLDivElement>(null);

  // One shared reveal for the whole grid: an ellipsized cell has no way to show
  // its full value or let you copy it, so on hover/focus of a cell that is
  // actually clipped (`scrollWidth > clientWidth`) we anchor a small panel to it.
  // A single instance, not one per cell — safe against the virtualized row count.
  const panelRef = useRef<HTMLDivElement>(null);
  const [reveal, setReveal] = useState<Reveal | null>(null);
  const openReveal = useCallback((el: HTMLElement) => {
    if (el.scrollWidth <= el.clientWidth) return;
    const text = el.dataset.full;
    if (!text) return;
    setReveal({ text, rect: el.getBoundingClientRect() });
  }, []);
  const closeReveal = useCallback((e: React.SyntheticEvent) => {
    // Keep the panel while focus/pointer moves into it (the copy button lives there).
    const next = (e as React.FocusEvent).relatedTarget as Node | null;
    if (next instanceof Node && panelRef.current?.contains(next)) return;
    setReveal(null);
  }, []);

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
  const template = [
    selectable ? `${SELECT_COL_WIDTH}px` : null,
    ...columns.map((c) =>
      c.width ? `${c.width}px` : `minmax(${FLEX_MIN_WIDTH}px, 1fr)`,
    ),
  ]
    .filter(Boolean)
    .join(" ");
  const minInline =
    (selectable ? SELECT_COL_WIDTH : 0) +
    columns.reduce((sum, c) => sum + (c.width ?? FLEX_MIN_WIDTH), 0);

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

  const loadedKeys = rows.map((r) => rowKey(r.original as T));
  const selectedCount = selected
    ? loadedKeys.filter((k) => selected.has(k)).length
    : 0;
  const allSelected =
    loadedKeys.length > 0 && selectedCount === loadedKeys.length;

  return (
    <div
      ref={scrollRef}
      className={styles.scroll}
      onScroll={reveal ? () => setReveal(null) : undefined}
    >
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
          {selectable ? (
            <div role="columnheader" className={`${styles.cell} ${styles.headCell}`}>
              <Checkbox
                size="xs"
                aria-label={
                  allSelected ? "Deselect all on this page" : "Select all on this page"
                }
                checked={allSelected}
                indeterminate={selectedCount > 0 && !allSelected}
                onChange={() => onToggleAll?.(loadedKeys, allSelected)}
              />
            </div>
          ) : null}
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
            const key = rowKey(original);
            return (
              <div
                key={key}
                role="row"
                aria-rowindex={vi.index + 2}
                data-selected={selected?.has(key) || undefined}
                className={`${styles.row} ${styles.bodyRow} ${onRowClick ? styles.clickable : ""}`}
                onClick={onRowClick ? () => onRowClick(original) : undefined}
                style={{ transform: `translateY(${vi.start}px)` }}
              >
                {selectable ? (
                  <div
                    role="gridcell"
                    className={styles.cell}
                    onClick={(e) => e.stopPropagation()}
                  >
                    <Checkbox
                      size="xs"
                      aria-label={`Select row ${key}`}
                      checked={selected?.has(key) ?? false}
                      onChange={() => onToggleRow?.(key)}
                    />
                  </div>
                ) : null}
                {columns.map((c) => {
                  const value = c.accessor(original);
                  const full = plainText(value);
                  return (
                    <div
                      key={c.id}
                      role="gridcell"
                      data-numeric={c.numeric || undefined}
                      data-full={full}
                      className={`${styles.cell} ${c.numeric ? styles.num : ""}`}
                      // An ellipsized cell still has to be readable in full: the
                      // title is the always-there fallback; the shared panel
                      // (hover / keyboard focus) adds copy. Only free-text cells
                      // opt into the tab stop — numeric counts never truncate.
                      title={full}
                      tabIndex={!c.numeric && full ? 0 : undefined}
                      onPointerEnter={(e) => openReveal(e.currentTarget)}
                      onPointerLeave={closeReveal}
                      onFocus={(e) => openReveal(e.currentTarget)}
                      onBlur={closeReveal}
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

      {reveal ? (
        <Portal>
          <div
            ref={panelRef}
            className={styles.reveal}
            role="dialog"
            aria-label="Full value"
            style={{
              insetInlineStart: Math.min(
                reveal.rect.left,
                window.innerWidth - 360,
              ),
              insetBlockStart: reveal.rect.bottom + 4,
            }}
            onPointerLeave={closeReveal}
          >
            <span className={styles.revealText}>{reveal.text}</span>
            <CopyButton value={reveal.text} timeout={1500}>
              {({ copied, copy }) => (
                <button
                  type="button"
                  className={styles.revealCopy}
                  onClick={copy}
                  onBlur={closeReveal}
                >
                  {copied ? "Copied" : "Copy"}
                </button>
              )}
            </CopyButton>
          </div>
        </Portal>
      ) : null}
    </div>
  );
}
