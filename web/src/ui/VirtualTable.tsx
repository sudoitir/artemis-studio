import { useCallback, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  ActionIcon,
  Checkbox,
  CopyButton,
  Portal,
  VisuallyHidden,
} from "@mantine/core";
import { IconDots } from "@tabler/icons-react";
import { tableFeatures, useTable, type ColumnDef } from "@tanstack/react-table";
import {
  defaultRangeExtractor,
  useVirtualizer,
  type Range,
} from "@tanstack/react-virtual";

import { AnchoredMenu } from "./AnchoredMenu.tsx";
import { anchorBelow, clampToViewport, type MenuAnchor } from "./menuAnchor.ts";
import { nextCell, resolveRow, type GridPos } from "./rovingGrid.ts";
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

/** What a row menu's items get: a way to close the menu, and to put focus back on the row. */
export interface RowMenuContext {
  close: () => void;
  /**
   * Puts focus back on the row's Actions control, scrolling it into view if it has left; on the
   * grid when the row is gone. For a dialog opened from the menu to call when it closes.
   */
  restoreFocus: () => void;
}

/** A per-row action menu (ADR-0107): its items are rendered only while it is open. */
export interface RowMenu<T> {
  /** Names the row in "Actions for <label>". */
  label: (row: T) => string;
  render: (row: T, context: RowMenuContext) => React.ReactNode;
}

const ROW_HEIGHT = 36;
/** How narrow a free-text column may get before the grid scrolls instead. */
const FLEX_MIN_WIDTH = 180;
/** The leading checkbox column's fixed track. */
const SELECT_COL_WIDTH = 40;
/** The trailing actions column's fixed track. */
const ACTIONS_COL_WIDTH = 44;
/** The header row's key in the focus model. */
const HEADER = null;
/** The elements that take focus themselves when they are a cell's one control. */
const WIDGETS = 'a[href], button, input, select, textarea, [role="button"], [role="checkbox"]';

/** The hover title for a cell, when its value is something a tooltip can say. */
function plainText(value: unknown): string | undefined {
  if (typeof value === "string") return value || undefined;
  if (typeof value === "number" || typeof value === "bigint")
    return String(value);
  return undefined;
}

function isRtl(): boolean {
  return (
    document.dir === "rtl" ||
    getComputedStyle(document.documentElement).direction === "rtl"
  );
}

/** A cell's single enabled control, which then takes the cell's focus; otherwise the cell itself. */
function focusTarget(cell: HTMLElement): HTMLElement {
  const widgets = [...cell.querySelectorAll<HTMLElement>(WIDGETS)].filter(
    (el) => !(el as HTMLButtonElement).disabled,
  );
  return widgets.length === 1 ? widgets[0] : cell;
}

interface Reveal {
  text: string;
  /** Viewport rect of the cell the panel is anchored to. */
  rect: DOMRect;
}

interface OpenMenu {
  key: string;
  anchor: MenuAnchor;
}

interface VirtualTableProps<T> {
  columns: GridColumn<T>[];
  data: T[];
  /** What the grid lists, as its accessible name: "Queues", "Connections". */
  label?: string;
  sort?: string;
  onSortChange?: (sort: string | undefined) => void;
  /** Activating a row, by click or by Enter on any of its cells. */
  onRowClick?: (row: T) => void;
  rowKey: (row: T) => string;
  emptyLabel?: React.ReactNode;
  /** An extra class per row, for state the caller owns — a live tail's fresh rows. */
  rowClassName?: (row: T) => string | undefined;
  /** Opt-in leading checkbox column. Selection state is owned by the caller (ephemeral React state). */
  selectable?: boolean;
  selected?: ReadonlySet<string>;
  onToggleRow?: (key: string) => void;
  /** Header select-all across the loaded page. `allSelected` is the current state; the caller flips it. */
  onToggleAll?: (keys: string[], allSelected: boolean) => void;
  /**
   * Called when the grid's own scroll leaves or returns to the top. A live feed
   * that prepends rows moves the content under a reader who has scrolled away, so
   * the caller needs to know in order to hold new rows back.
   */
  onAtTopChange?: (atTop: boolean) => void;
  /** A per-row action menu, opened by right-click, by the row's Actions control, or by Shift+F10. */
  rowMenu?: RowMenu<T>;
  /** Sized to its rows, up to a short cap, instead of to the viewport: a handful of rows in a pane. */
  compact?: boolean;
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
 *
 * <p>The keyboard model is the WAI-ARIA grid (ADR-0108): the grid is one tab
 * stop, the arrow keys move a roving focus between cells (the header row
 * included), Enter activates a row, Space selects it, Shift+F10 opens its menu,
 * and Ctrl/Cmd+C copies a focused cell. The focused cell is remembered by row
 * key, so a refresh or a re-sort does not move it to another row, and its row is
 * always rendered however far it is scrolled.
 */
export function VirtualTable<T>({
  columns,
  data,
  label,
  sort,
  onSortChange,
  onRowClick,
  rowKey,
  emptyLabel,
  rowClassName,
  selectable,
  selected,
  onToggleRow,
  onToggleAll,
  onAtTopChange,
  rowMenu,
  compact,
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
  const loadedKeys = rows.map((r) => rowKey(r.original as T));

  const scrollRef = useRef<HTMLDivElement>(null);
  const gridRef = useRef<HTMLDivElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);

  // ── Focus model ────────────────────────────────────────────────────────────
  // The active cell, by row key (null for the header row) and column index. A
  // key rather than an index, so that a refresh that reorders rows keeps focus
  // on the row the operator was on.
  const firstDataCol = selectable ? 1 : 0;
  const colCount =
    (selectable ? 1 : 0) + columns.length + (rowMenu ? 1 : 0);
  const [active, setActive] = useState<{ key: string | null; col: number }>({
    key: HEADER,
    col: firstDataCol,
  });
  const touched = useRef(false);
  const lastIndex = useRef(1);
  const pendingFocus = useRef(false);
  const focusWithin = useRef(false);
  const quietScrollUntil = useRef(0);

  const activeRow = touched.current
    ? resolveRow(loadedKeys, active.key, lastIndex.current)
    : loadedKeys.length > 0
      ? 1
      : 0;
  const activeCol = Math.min(active.col, Math.max(colCount - 1, 0));
  if (activeRow > 0) lastIndex.current = activeRow;

  const activeIndexRef = useRef(activeRow - 1);
  activeIndexRef.current = activeRow - 1;

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

  // A copy made from the keyboard is announced, since nothing on screen changes. The live region is
  // mounted by the first copy, empty, before its text arrives: a region that exists before it
  // changes is what screen readers announce reliably, and a grid nobody copies from adds none.
  const [announcement, setAnnouncement] = useState<string | null>(null);

  // The header's height, so a row scrolled into view is not left under the sticky header.
  const [headerHeight, setHeaderHeight] = useState(ROW_HEIGHT);
  useLayoutEffect(() => {
    const h = headerRef.current?.getBoundingClientRect().height;
    if (h && h > 0 && h < ROW_HEIGHT * 3 && Math.abs(h - headerHeight) > 0.5) {
      setHeaderHeight(h);
    }
  }, [headerHeight, rows.length]);

  const rangeExtractor = useCallback((range: Range) => {
    // The focused row stays rendered wherever it is scrolled, so focus is never
    // dropped to <body> by the virtualizer unmounting its cell.
    const base = defaultRangeExtractor(range);
    const keep = activeIndexRef.current;
    if (keep < 0 || keep >= range.count || base.includes(keep)) return base;
    return [...base, keep].sort((a, b) => a - b);
  }, []);

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 14,
    rangeExtractor,
    scrollPaddingStart: headerHeight,
  });

  const cellAt = useCallback((row: number, col: number): HTMLElement | null => {
    return (
      gridRef.current?.querySelector<HTMLElement>(
        `[data-grid-row="${row}"] > [data-grid-col="${col}"]`,
      ) ?? null
    );
  }, []);

  const moveTo = useCallback(
    (pos: GridPos) => {
      touched.current = true;
      pendingFocus.current = true;
      const key = pos.row === 0 ? HEADER : loadedKeys[pos.row - 1] ?? HEADER;
      setActive({ key, col: pos.col });
      if (pos.row > 0) {
        quietScrollUntil.current = performance.now() + 250;
        virtualizer.scrollToIndex(pos.row - 1, { align: "auto" });
      }
    },
    [loadedKeys, virtualizer],
  );

  // After every render: exactly one element of the grid is in the tab order —
  // the active cell's focus target — and, after a keyboard move, it takes focus.
  // Also recovers focus when the focused row was removed by a refresh.
  useLayoutEffect(() => {
    const grid = gridRef.current;
    if (!grid) return;
    let target: HTMLElement | null = null;
    for (const cell of grid.querySelectorAll<HTMLElement>("[data-grid-col]")) {
      const row = Number(cell.parentElement?.dataset.gridRow);
      const col = Number(cell.dataset.gridCol);
      const isActive = row === activeRow && col === activeCol;
      const focusable = focusTarget(cell);
      cell.tabIndex = isActive && focusable === cell ? 0 : -1;
      for (const widget of cell.querySelectorAll<HTMLElement>(WIDGETS)) {
        widget.tabIndex = isActive && widget === focusable ? 0 : -1;
      }
      if (isActive) target = focusable;
    }
    const lost =
      focusWithin.current &&
      (document.activeElement === document.body || document.activeElement === null);
    if (target && (pendingFocus.current || lost)) {
      pendingFocus.current = false;
      target.focus({ preventScroll: true });
    }
  });

  // ── Row menu ───────────────────────────────────────────────────────────────
  const [menu, setMenu] = useState<OpenMenu | null>(null);
  const suppressContextMenuUntil = useRef(0);
  const dataRef = useRef({ loadedKeys, rows });
  dataRef.current = { loadedKeys, rows };

  const restoreFocusTo = useCallback(
    (key: string) => {
      const { loadedKeys: keys } = dataRef.current;
      const index = keys.indexOf(key);
      touched.current = true;
      pendingFocus.current = true;
      focusWithin.current = true;
      if (index < 0) {
        // The row is gone: focus the neighbour that took its place, or the header.
        setActive((prev) => ({ key: keys[Math.min(lastIndex.current, keys.length) - 1] ?? HEADER, col: prev.col }));
        return;
      }
      setActive({ key, col: colCount - 1 });
      virtualizer.scrollToIndex(index, { align: "auto" });
    },
    [colCount, virtualizer],
  );

  const openMenu = useCallback(
    (key: string, anchor: MenuAnchor) => {
      setReveal(null);
      setMenu({ key, anchor });
    },
    [],
  );

  const menuRef = useRef(menu);
  menuRef.current = menu;
  const closeMenu = useCallback(
    (restore: boolean) => {
      const current = menuRef.current;
      setMenu(null);
      if (current && restore) restoreFocusTo(current.key);
    },
    [restoreFocusTo],
  );

  const menuRow = menu
    ? (rows[loadedKeys.indexOf(menu.key)]?.original as T | undefined)
    : undefined;

  // ── Events ─────────────────────────────────────────────────────────────────
  const posOf = (el: Element | null): GridPos | null => {
    const cell = el?.closest<HTMLElement>("[data-grid-col]");
    const row = cell?.parentElement?.dataset.gridRow;
    if (!cell || row === undefined) return null;
    return { row: Number(row), col: Number(cell.dataset.gridCol) };
  };

  const onGridFocus = (e: React.FocusEvent<HTMLDivElement>) => {
    focusWithin.current = true;
    const pos = posOf(e.target);
    if (!pos) return;
    touched.current = true;
    const key = pos.row === 0 ? HEADER : loadedKeys[pos.row - 1] ?? HEADER;
    if (key !== active.key || pos.col !== active.col) setActive({ key, col: pos.col });
    // The reveal follows focus: it closes on the cell focus left, and opens on the one it reached
    // if that cell's value is clipped.
    setReveal(null);
    const cell = e.target.closest<HTMLElement>("[data-grid-col]");
    if (cell && e.target === cell) openReveal(cell);
  };

  const onGridBlur = (e: React.FocusEvent<HTMLDivElement>) => {
    const next = e.relatedTarget as Node | null;
    if (next && gridRef.current?.contains(next)) return;
    if (next && panelRef.current?.contains(next)) return;
    focusWithin.current = false;
    closeReveal(e);
  };

  const copyCell = (cell: HTMLElement) => {
    const text = cell.dataset.full;
    if (!text || !navigator.clipboard) return false;
    setAnnouncement((prev) => (prev === null ? "" : prev));
    void navigator.clipboard.writeText(text).then(
      () => setAnnouncement(`Copied ${text}`),
      () => setAnnouncement("Copy failed: the browser refused access to the clipboard."),
    );
    return true;
  };

  const onGridKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const pos = posOf(e.target as Element);
    if (!pos) return;
    const target = e.target as HTMLElement;
    const cell = target.closest<HTMLElement>("[data-grid-col]")!;
    const onWidget = target !== cell;
    const body = pos.row > 0;
    const row = body ? (rows[pos.row - 1]?.original as T | undefined) : undefined;
    const key = body ? loadedKeys[pos.row - 1] : undefined;

    // The row menu, from the keyboard: Shift+F10 or the ContextMenu key.
    if ((e.key === "F10" && e.shiftKey) || e.key === "ContextMenu") {
      if (!rowMenu || !key) return;
      e.preventDefault();
      suppressContextMenuUntil.current = performance.now() + 500;
      const trigger = cellAt(pos.row, colCount - 1)?.querySelector("button");
      openMenu(key, anchorBelow(trigger ?? cell));
      return;
    }

    if (e.key === "Escape" && reveal) {
      setReveal(null);
      return;
    }

    if ((e.key === "c" || e.key === "C") && (e.ctrlKey || e.metaKey) && !e.altKey) {
      if (window.getSelection()?.toString()) return;
      if (copyCell(cell)) e.preventDefault();
      return;
    }

    if (e.key === "Enter" && body && !onWidget && row !== undefined && onRowClick) {
      e.preventDefault();
      onRowClick(row);
      return;
    }

    if (e.key === " " && body && !onWidget && selectable && key !== undefined) {
      e.preventDefault();
      onToggleRow?.(key);
      return;
    }

    if (e.altKey) return;
    const scroll = scrollRef.current;
    const page = Math.max(
      1,
      Math.floor(((scroll?.clientHeight ?? ROW_HEIGHT * 10) - headerHeight) / ROW_HEIGHT) - 1,
    );
    const next = nextCell(pos, e, {
      rows: rows.length,
      cols: colCount,
      page,
      rtl: isRtl(),
    });
    if (!next) return;
    e.preventDefault();
    if (next.row !== pos.row || next.col !== pos.col) moveTo(next);
  };

  const onBodyContextMenu = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!rowMenu) return;
    if (performance.now() < suppressContextMenuUntil.current) {
      // The keyboard already opened the menu; this is the browser's echo of the same key.
      e.preventDefault();
      return;
    }
    const target = e.target as Element;
    // The browser's own menu stays where it is worth more than ours: on a link (open in a new tab),
    // in a field, when Shift asks for it, and when text is selected (copy).
    if (
      e.shiftKey ||
      target.closest("a[href], input, textarea, select, [contenteditable]") ||
      window.getSelection()?.toString()
    ) {
      return;
    }
    const pos = posOf(target);
    if (!pos || pos.row === 0) return;
    const key = loadedKeys[pos.row - 1];
    if (key === undefined) return;
    e.preventDefault();
    touched.current = true;
    focusWithin.current = true;
    setActive({ key, col: pos.col });
    openMenu(key, clampToViewport({ x: e.clientX, y: e.clientY }));
  };

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
    rowMenu ? `${ACTIONS_COL_WIDTH}px` : null,
  ]
    .filter(Boolean)
    .join(" ");
  const minInline =
    (selectable ? SELECT_COL_WIDTH : 0) +
    columns.reduce((sum, c) => sum + (c.width ?? FLEX_MIN_WIDTH), 0) +
    (rowMenu ? ACTIONS_COL_WIDTH : 0);

  const sortField = sort?.replace(/^-/, "");
  const sortDesc = sort?.startsWith("-");

  const nextSort = (key: string): string | undefined => {
    if (sortField !== key) return key;
    if (!sortDesc) return `-${key}`;
    return undefined;
  };

  const menuContext = useMemo<RowMenuContext | null>(
    () =>
      menu
        ? {
            close: () => closeMenu(false),
            restoreFocus: () => restoreFocusTo(menu.key),
          }
        : null,
    [menu, closeMenu, restoreFocusTo],
  );

  if (rows.length === 0 && emptyLabel) {
    return <div className={styles.empty}>{emptyLabel}</div>;
  }

  const selectedCount = selected
    ? loadedKeys.filter((k) => selected.has(k)).length
    : 0;
  const allSelected =
    loadedKeys.length > 0 && selectedCount === loadedKeys.length;
  const actionsCol = colCount - 1;
  const rtl = typeof document !== "undefined" && document.dir === "rtl";

  return (
    <div
      ref={scrollRef}
      className={styles.scroll}
      data-compact={compact || undefined}
      onScroll={(e) => {
        if (reveal && performance.now() > quietScrollUntil.current) setReveal(null);
        if (menu) closeMenu(false);
        onAtTopChange?.(e.currentTarget.scrollTop <= 4);
      }}
    >
      <div
        ref={gridRef}
        className={styles.grid}
        role="grid"
        aria-label={label}
        aria-rowcount={rows.length + 1}
        onFocus={onGridFocus}
        onBlur={onGridBlur}
        onKeyDown={onGridKeyDown}
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
          ref={headerRef}
          className={`${styles.row} ${styles.headRow}`}
          role="row"
          aria-rowindex={1}
          data-grid-row={0}
        >
          {selectable ? (
            <div
              role="columnheader"
              data-grid-col={0}
              className={`${styles.cell} ${styles.headCell} ${styles.selectCell}`}
            >
              <Checkbox
                size="xs"
                aria-label={
                  allSelected
                    ? "Deselect all on this page"
                    : "Select all on this page"
                }
                checked={allSelected}
                indeterminate={selectedCount > 0 && !allSelected}
                onChange={() => onToggleAll?.(loadedKeys, allSelected)}
              />
            </div>
          ) : null}
          {columns.map((c, i) => {
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
                data-grid-col={firstDataCol + i}
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
          {rowMenu ? (
            <div
              role="columnheader"
              data-grid-col={actionsCol}
              className={`${styles.cell} ${styles.headCell}`}
            >
              <VisuallyHidden>Actions</VisuallyHidden>
            </div>
          ) : null}
        </div>

        <div
          className={styles.body}
          role="rowgroup"
          style={{ height: virtualizer.getTotalSize() }}
          onContextMenu={onBodyContextMenu}
        >
          {virtualizer.getVirtualItems().map((vi) => {
            const original = rows[vi.index].original as T;
            const key = rowKey(original);
            const rowLabel = rowMenu?.label(original) ?? key;
            return (
              <div
                key={key}
                role="row"
                aria-rowindex={vi.index + 2}
                aria-selected={selectable ? selected?.has(key) ?? false : undefined}
                data-grid-row={vi.index + 1}
                data-selected={selected?.has(key) || undefined}
                data-menu-open={menu?.key === key || undefined}
                className={`${styles.row} ${styles.bodyRow} ${onRowClick ? styles.clickable : ""} ${rowClassName?.(original) ?? ""}`}
                onClick={
                  onRowClick
                    ? (e) => {
                        // A control inside the row acts for itself, not for the row.
                        if ((e.target as Element).closest(WIDGETS)) return;
                        onRowClick(original);
                      }
                    : undefined
                }
                style={{ transform: `translateY(${vi.start}px)` }}
              >
                {selectable ? (
                  <div
                    role="gridcell"
                    data-grid-col={0}
                    className={`${styles.cell} ${styles.selectCell}`}
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
                {columns.map((c, i) => {
                  const value = c.accessor(original);
                  const full = plainText(value);
                  return (
                    <div
                      key={c.id}
                      role="gridcell"
                      data-numeric={c.numeric || undefined}
                      data-full={full}
                      data-grid-col={firstDataCol + i}
                      className={`${styles.cell} ${c.numeric ? styles.num : ""}`}
                      // An ellipsized cell still has to be readable in full: the
                      // title is the always-there fallback; the shared panel
                      // (hover / keyboard focus) adds copy.
                      title={full}
                      onPointerEnter={(e) => openReveal(e.currentTarget)}
                      onPointerLeave={closeReveal}
                    >
                      {c.cell ? c.cell(original) : String(value ?? "")}
                    </div>
                  );
                })}
                {rowMenu ? (
                  <div
                    role="gridcell"
                    data-grid-col={actionsCol}
                    className={`${styles.cell} ${styles.actionsCell}`}
                  >
                    <ActionIcon
                      variant="subtle"
                      color="gray"
                      size="sm"
                      aria-label={`Actions for ${rowLabel}`}
                      aria-haspopup="menu"
                      aria-expanded={menu?.key === key}
                      onClick={(e) => {
                        e.stopPropagation();
                        if (menu?.key === key) {
                          closeMenu(true);
                          return;
                        }
                        openMenu(key, anchorBelow(e.currentTarget));
                      }}
                    >
                      <IconDots size={16} aria-hidden />
                    </ActionIcon>
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
      </div>

      {rowMenu && menu && menuRow !== undefined && menuContext ? (
        <AnchoredMenu
          opened
          anchor={menu.anchor}
          label={`Actions for ${rowMenu.label(menuRow)}`}
          onClose={() => closeMenu(true)}
        >
          {rowMenu.render(menuRow, menuContext)}
        </AnchoredMenu>
      ) : null}

      {announcement !== null ? (
        <VisuallyHidden role="status" aria-live="polite">
          {announcement}
        </VisuallyHidden>
      ) : null}

      {reveal ? (
        <Portal>
          <div
            ref={panelRef}
            className={styles.reveal}
            role="dialog"
            aria-label="Full value"
            style={{
              insetInlineStart: rtl
                ? Math.min(window.innerWidth - reveal.rect.right, window.innerWidth - 360)
                : Math.min(reveal.rect.left, window.innerWidth - 360),
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
