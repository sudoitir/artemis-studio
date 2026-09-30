import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { ActionIcon, Checkbox, CopyButton, Portal, VisuallyHidden } from '@mantine/core';
import { IconDots } from '@tabler/icons-react';
import { tableFeatures, useTable, type ColumnDef } from '@tanstack/react-table';
import { defaultRangeExtractor, useVirtualizer, type Range } from '@tanstack/react-virtual';

import { AnchoredMenu } from './AnchoredMenu.tsx';
import { anchorBelow, clampToViewport, type MenuAnchor } from './menuAnchor.ts';
import { nextCell, resolveRow, type GridPos } from './rovingGrid.ts';
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
/** The widest a column fits itself to on its own (ADR-0116); a viewer can go further. */
const FIT_MAX_WIDTH = 480;
/** The narrowest a viewer can drag a column. */
const RESIZE_MIN_WIDTH = 48;
/** One Ctrl+Shift+Arrow step. */
const RESIZE_STEP = 16;
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
  if (typeof value === 'string') return value || undefined;
  if (typeof value === 'number' || typeof value === 'bigint') return String(value);
  return undefined;
}

function isRtl(): boolean {
  return document.dir === 'rtl' || getComputedStyle(document.documentElement).direction === 'rtl';
}

/** Widths a viewer set, as stored: only current columns, only sane numbers. */
function readWidths(storageKey: string | undefined, ids: string[]): Record<string, number> {
  if (!storageKey) return {};
  try {
    const raw: unknown = JSON.parse(localStorage.getItem(`as.grid.${storageKey}`) ?? '{}');
    if (!raw || typeof raw !== 'object') return {};
    const out: Record<string, number> = {};
    for (const [id, w] of Object.entries(raw as Record<string, unknown>)) {
      if (ids.includes(id) && typeof w === 'number' && Number.isFinite(w) && w >= RESIZE_MIN_WIDTH && w <= 4000) {
        out[id] = Math.round(w);
      }
    }
    return out;
  } catch {
    return {};
  }
}

function writeWidths(storageKey: string | undefined, widths: Record<string, number>) {
  if (!storageKey) return;
  try {
    localStorage.setItem(`as.grid.${storageKey}`, JSON.stringify(widths));
  } catch {
    // Storage full or blocked: the widths still hold for this visit.
  }
}

/** The width a cell's content needs, padding included; a header's sort button is measured itself. */
function contentWidth(cell: HTMLElement): number {
  const button = cell.querySelector<HTMLElement>(':scope > button');
  const style = getComputedStyle(cell);
  const padding = (Number.parseFloat(style.paddingInlineStart) || 0) + (Number.parseFloat(style.paddingInlineEnd) || 0);
  return button ? button.scrollWidth + padding : cell.scrollWidth;
}

/** A cell's single enabled control, which then takes the cell's focus; otherwise the cell itself. */
function focusTarget(cell: HTMLElement): HTMLElement {
  const widgets = [...cell.querySelectorAll<HTMLElement>(WIDGETS)].filter((el) => !(el as HTMLButtonElement).disabled);
  return widgets.length === 1 ? widgets[0] : cell;
}

/**
 * Makes exactly one element of the grid tabbable: the active cell's focus target. Returns that
 * target, so the caller can move focus to it.
 */
function syncTabOrder(grid: HTMLElement, activeRow: number, activeCol: number): HTMLElement | null {
  let target: HTMLElement | null = null;
  for (const cell of grid.querySelectorAll<HTMLElement>('[data-grid-col]')) {
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
  return target;
}

/** How a sortable column stands: whether the grid is sorted by it, and which way. */
interface ColumnSorting {
  active: boolean;
  desc: boolean;
}

/** The grid's `aria-sort` for a column: none when it cannot sort, else which way it sorts now. */
function ariaSortOf(sorting: ColumnSorting | null): 'ascending' | 'descending' | 'none' | undefined {
  if (!sorting) return undefined;
  if (!sorting.active) return 'none';
  return sorting.desc ? 'descending' : 'ascending';
}

/** The arrow after a sortable header's name: which way it sorts, and nothing when it does not. */
function sortMark(active: boolean, desc: boolean): string {
  if (!active) return '';
  return desc ? ' ▾' : ' ▴';
}

/**
 * The single source of truth for column geometry. Header and body rows are
 * both grid containers over this one track list, so they cannot drift apart
 * the way two independently laid-out tables can. The floors add up to the
 * grid's `min-inline-size`, which is also the width body rows resolve
 * against — without it the tracks would overflow a grid box still pinned to
 * the viewport, and the rows would be laid out narrower than the header.
 */
function gridTracks<T>(
  columns: GridColumn<T>[],
  widths: Record<string, number>,
  fits: Record<string, number>,
  selectable: boolean,
  hasMenu: boolean,
): { template: string; minInline: number } {
  // A declared `width` is the least a fixed column gets: a value that needs more (MULTICAST in a
  // 96 px type column, a longer translation) widens it rather than being cut.
  const floorOf = (c: GridColumn<T>) =>
    widths[c.id] ?? (c.width ? Math.max(c.width, fits[c.id] ?? 0) : Math.max(FLEX_MIN_WIDTH, fits[c.id] ?? 0));
  const template = [
    selectable ? `${SELECT_COL_WIDTH}px` : null,
    ...columns.map((c) => (widths[c.id] || c.width ? `${floorOf(c)}px` : `minmax(${floorOf(c)}px, 1fr)`)),
    hasMenu ? `${ACTIONS_COL_WIDTH}px` : null,
  ]
    .filter(Boolean)
    .join(' ');
  const minInline =
    (selectable ? SELECT_COL_WIDTH : 0) +
    columns.reduce((sum, c) => sum + floorOf(c), 0) +
    (hasMenu ? ACTIONS_COL_WIDTH : 0);
  return { template, minInline };
}

/** The text of a cell whose column draws nothing of its own: the value, or nothing for none. */
function defaultText(value: unknown): string {
  // Accessors return scalars; an object here would be a column that needs its own `cell`.
  return String((value ?? '') as string | number | boolean | bigint);
}

/** The keys that open a row's menu: Shift+F10 or the ContextMenu key. */
function isMenuKey(e: React.KeyboardEvent): boolean {
  return e.key === 'ContextMenu' || (e.key === 'F10' && e.shiftKey);
}

function isCopyKey(e: React.KeyboardEvent): boolean {
  return (e.key === 'c' || e.key === 'C') && (e.ctrlKey || e.metaKey) && !e.altKey;
}

/** Ctrl+Shift+Left/Right, which resizes the column of a header cell (ADR-0116). */
function isResizeKey(e: React.KeyboardEvent): boolean {
  return e.ctrlKey && e.shiftKey && !e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight');
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
  /**
   * Remembers the column widths each viewer sets, in this browser (ADR-0116). Stable and unique
   * per grid; a plugin prefixes its id. Without one, widths last as long as the grid is shown.
   */
  storageKey?: string;
}

/** The header's select-all checkbox, across the rows loaded. */
function SelectAllCell({
  keys,
  selected,
  onToggleAll,
}: Readonly<{
  keys: string[];
  selected: ReadonlySet<string> | undefined;
  onToggleAll: ((keys: string[], allSelected: boolean) => void) | undefined;
}>) {
  const count = selected ? keys.filter((k) => selected.has(k)).length : 0;
  const all = keys.length > 0 && count === keys.length;
  return (
    <div role="columnheader" data-grid-col={0} className={`${styles.cell} ${styles.headCell} ${styles.selectCell}`}>
      <Checkbox
        size="xs"
        aria-label={all ? 'Deselect all on this page' : 'Select all on this page'}
        checked={all}
        indeterminate={count > 0 && !all}
        onChange={() => onToggleAll?.(keys, all)}
      />
    </div>
  );
}

/** A body row's selection checkbox. */
function SelectCell({
  rowKey,
  checked,
  onToggle,
}: Readonly<{ rowKey: string; checked: boolean; onToggle: ((key: string) => void) | undefined }>) {
  return (
    <div
      role="gridcell"
      data-grid-col={0}
      className={`${styles.cell} ${styles.selectCell}`}
      onClick={(e) => e.stopPropagation()}
    >
      <Checkbox size="xs" aria-label={`Select row ${rowKey}`} checked={checked} onChange={() => onToggle?.(rowKey)} />
    </div>
  );
}

interface ColumnHeaderProps<T> {
  column: GridColumn<T>;
  /** Its position in the grid's focus model. */
  col: number;
  /** Null when the column cannot sort here. */
  sorting: ColumnSorting | null;
  onSort: () => void;
  onResizeStart: (e: React.PointerEvent<HTMLElement>) => void;
  onFit: () => void;
}

/** A column's header cell: its name or sort button, and the handle that resizes it. */
function ColumnHeader<T>({ column, col, sorting, onSort, onResizeStart, onFit }: Readonly<ColumnHeaderProps<T>>) {
  return (
    <div
      role="columnheader"
      aria-sort={ariaSortOf(sorting)}
      data-numeric={column.numeric || undefined}
      data-grid-col={col}
      className={`${styles.cell} ${styles.headCell}`}
      aria-description="Ctrl+Shift+Left or Right resizes this column."
    >
      {sorting ? (
        <button type="button" className={styles.sortButton} onClick={onSort}>
          {column.header}
          <span aria-hidden="true">{sortMark(sorting.active, sorting.desc)}</span>
        </button>
      ) : (
        column.header
      )}
      {/* A pointer affordance for what Ctrl+Shift+Arrow does from the keyboard. */}
      <span
        aria-hidden="true"
        className={styles.resizeHandle}
        onPointerDown={onResizeStart}
        onDoubleClick={(e) => {
          e.stopPropagation();
          onFit();
        }}
        onClick={(e) => e.stopPropagation()}
      />
    </div>
  );
}

interface DataCellProps<T> {
  column: GridColumn<T>;
  row: T;
  col: number;
  onReveal: (el: HTMLElement) => void;
  onHide: (e: React.SyntheticEvent) => void;
}

/** One value of a body row. */
function DataCell<T>({ column, row, col, onReveal, onHide }: Readonly<DataCellProps<T>>) {
  const value = column.accessor(row);
  const full = plainText(value);
  return (
    <div
      role="gridcell"
      data-numeric={column.numeric || undefined}
      data-full={full}
      data-grid-col={col}
      className={`${styles.cell} ${column.numeric ? styles.num : ''}`}
      // An ellipsized cell still has to be readable in full: the
      // title is the always-there fallback; the shared panel
      // (hover / keyboard focus) adds copy.
      title={full}
      onPointerEnter={(e) => onReveal(e.currentTarget)}
      onPointerLeave={onHide}
    >
      {column.cell ? column.cell(row) : defaultText(value)}
    </div>
  );
}

/** The trailing cell of a body row: the control that opens its menu, or closes it again. */
function ActionsCell({
  col,
  label,
  expanded,
  onToggle,
}: Readonly<{ col: number; label: string; expanded: boolean; onToggle: (trigger: HTMLElement) => void }>) {
  return (
    <div role="gridcell" data-grid-col={col} className={`${styles.cell} ${styles.actionsCell}`}>
      <ActionIcon
        variant="subtle"
        color="gray"
        size="sm"
        aria-label={`Actions for ${label}`}
        aria-haspopup="menu"
        aria-expanded={expanded}
        onClick={(e) => {
          e.stopPropagation();
          onToggle(e.currentTarget);
        }}
      >
        <IconDots size={16} aria-hidden />
      </ActionIcon>
    </div>
  );
}

/** The full value of a clipped cell, with a way to copy it, anchored to that cell. */
function RevealPanel({
  reveal,
  panelRef,
  onHide,
}: Readonly<{
  reveal: Reveal;
  panelRef: React.RefObject<HTMLDivElement | null>;
  onHide: (e: React.SyntheticEvent) => void;
}>) {
  const rtl = typeof document !== 'undefined' && document.dir === 'rtl';
  return (
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
        onPointerLeave={onHide}
      >
        <span className={styles.revealText}>{reveal.text}</span>
        <CopyButton value={reveal.text} timeout={1500}>
          {({ copied, copy }) => (
            <button type="button" className={styles.revealCopy} onClick={copy} onBlur={onHide}>
              {copied ? 'Copied' : 'Copy'}
            </button>
          )}
        </CopyButton>
      </div>
    </Portal>
  );
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
  storageKey,
}: Readonly<VirtualTableProps<T>>) {
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

  // ── Column widths (ADR-0116) ───────────────────────────────────────────────
  // `widths` are what the viewer set, and make a column a fixed track; `fits` are
  // the floors measured from content, for the columns the viewer has not sized.
  const columnIds = columns.map((c) => c.id);
  const columnKey = columnIds.join('\u0000');
  const [widths, setWidths] = useState<Record<string, number>>(() => readWidths(storageKey, columnIds));
  const [fits, setFits] = useState<Record<string, number>>({});
  const fittedFor = useRef<string | null>(null);

  // ── Focus model ────────────────────────────────────────────────────────────
  // The active cell, by row key (null for the header row) and column index. A
  // key rather than an index, so that a refresh that reorders rows keeps focus
  // on the row the operator was on.
  const firstDataCol = selectable ? 1 : 0;
  const colCount = (selectable ? 1 : 0) + columns.length + (rowMenu ? 1 : 0);
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
    : Math.min(loadedKeys.length, 1);
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

  /** The widest header or rendered cell of a column, within `cap`. */
  const measure = useCallback((col: number, cap: number): number => {
    const cells = gridRef.current?.querySelectorAll<HTMLElement>(`[data-grid-row] > [data-grid-col="${col}"]`) ?? [];
    let need = 0;
    for (const cell of cells) need = Math.max(need, contentWidth(cell));
    return Math.round(Math.min(need + 2, cap));
  }, []);

  const setWidth = useCallback(
    (id: string, width: number) => {
      setWidths((prev) => {
        const next = { ...prev, [id]: Math.round(Math.max(RESIZE_MIN_WIDTH, width)) };
        writeWidths(storageKey, next);
        return next;
      });
    },
    [storageKey],
  );

  const startResize = (e: React.PointerEvent<HTMLElement>, id: string) => {
    if (e.button !== 0) return;
    e.preventDefault();
    e.stopPropagation();
    const handle = e.currentTarget;
    const start = handle.parentElement!.getBoundingClientRect().width;
    const x0 = e.clientX;
    const sign = isRtl() ? -1 : 1;
    handle.setPointerCapture(e.pointerId);
    handle.dataset.active = 'true';
    const move = (ev: PointerEvent) =>
      setWidths((prev) => ({
        ...prev,
        [id]: Math.round(Math.max(RESIZE_MIN_WIDTH, start + (ev.clientX - x0) * sign)),
      }));
    const end = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', end);
      handle.removeEventListener('pointercancel', end);
      delete handle.dataset.active;
      setWidths((prev) => {
        writeWidths(storageKey, prev);
        return prev;
      });
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', end);
    handle.addEventListener('pointercancel', end);
  };

  const fitColumn = (id: string, col: number) => {
    setWidth(id, measure(col, Math.max(FLEX_MIN_WIDTH, scrollRef.current?.clientWidth ?? FIT_MAX_WIDTH)));
  };

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

  const rowsRendered = virtualizer.getVirtualItems().length > 0;
  // Fit once rows are rendered (the virtualizer's first pass renders none), and again when the columns change; never while scrolling, so no
  // column moves under the pointer.
  useLayoutEffect(() => {
    if (!rowsRendered || fittedFor.current === columnKey) return;
    fittedFor.current = columnKey;
    const next: Record<string, number> = {};
    columns.forEach((c, i) => {
      next[c.id] = measure(firstDataCol + i, FIT_MAX_WIDTH);
    });
    setFits(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [columnKey, rowsRendered, measure, firstDataCol]);

  const cellAt = useCallback((row: number, col: number): HTMLElement | null => {
    return gridRef.current?.querySelector<HTMLElement>(`[data-grid-row="${row}"] > [data-grid-col="${col}"]`) ?? null;
  }, []);

  const moveTo = useCallback(
    (pos: GridPos) => {
      touched.current = true;
      pendingFocus.current = true;
      const key = pos.row === 0 ? HEADER : (loadedKeys[pos.row - 1] ?? HEADER);
      setActive({ key, col: pos.col });
      if (pos.row > 0) {
        quietScrollUntil.current = performance.now() + 250;
        virtualizer.scrollToIndex(pos.row - 1, { align: 'auto' });
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
    const target = syncTabOrder(grid, activeRow, activeCol);
    const lost = focusWithin.current && (document.activeElement === document.body || document.activeElement === null);
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
      virtualizer.scrollToIndex(index, { align: 'auto' });
    },
    [colCount, virtualizer],
  );

  const openMenu = useCallback((key: string, anchor: MenuAnchor) => {
    setReveal(null);
    setMenu({ key, anchor });
  }, []);

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

  const menuRow = menu ? (rows[loadedKeys.indexOf(menu.key)]?.original as T | undefined) : undefined;

  // ── Events ─────────────────────────────────────────────────────────────────
  const posOf = (el: Element | null): GridPos | null => {
    const cell = el?.closest<HTMLElement>('[data-grid-col]');
    const row = cell?.parentElement?.dataset.gridRow;
    if (!cell || row === undefined) return null;
    return { row: Number(row), col: Number(cell.dataset.gridCol) };
  };

  const onGridFocus = (e: React.FocusEvent<HTMLDivElement>) => {
    focusWithin.current = true;
    const pos = posOf(e.target);
    if (!pos) return;
    touched.current = true;
    const key = pos.row === 0 ? HEADER : (loadedKeys[pos.row - 1] ?? HEADER);
    if (key !== active.key || pos.col !== active.col) setActive({ key, col: pos.col });
    // The reveal follows focus: it closes on the cell focus left, and opens on the one it reached
    // if that cell's value is clipped.
    setReveal(null);
    const cell = e.target.closest<HTMLElement>('[data-grid-col]');
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
    setAnnouncement((prev) => prev ?? '');
    void navigator.clipboard.writeText(text).then(
      () => setAnnouncement(`Copied ${text}`),
      () => setAnnouncement('Copy failed: the browser refused access to the clipboard.'),
    );
    return true;
  };

  /** Where a key was pressed: the cell, and the row it belongs to when it is in the body. */
  interface KeyTarget {
    pos: GridPos;
    cell: HTMLElement;
    onWidget: boolean;
    row: T | undefined;
    key: string | undefined;
  }

  // Each key handler reports whether the key was its own.
  const rowMenuKey = (e: React.KeyboardEvent, { pos, cell, key }: KeyTarget): boolean => {
    if (!isMenuKey(e)) return false;
    if (rowMenu && key) {
      e.preventDefault();
      suppressContextMenuUntil.current = performance.now() + 500;
      const trigger = cellAt(pos.row, colCount - 1)?.querySelector('button');
      openMenu(key, anchorBelow(trigger ?? cell));
    }
    return true;
  };

  const escapeKey = (e: React.KeyboardEvent): boolean => {
    if (e.key !== 'Escape' || !reveal) return false;
    setReveal(null);
    return true;
  };

  const copyKey = (e: React.KeyboardEvent, { cell }: KeyTarget): boolean => {
    if (!isCopyKey(e)) return false;
    if (!window.getSelection()?.toString() && copyCell(cell)) e.preventDefault();
    return true;
  };

  /** Enter activates a row and Space selects it, from the row's cells but not from a control in one. */
  const activateKey = (e: React.KeyboardEvent, { pos, onWidget, row, key }: KeyTarget): boolean => {
    if (pos.row === 0 || onWidget) return false;
    if (e.key === 'Enter' && row !== undefined && onRowClick) {
      e.preventDefault();
      onRowClick(row);
      return true;
    }
    if (e.key === ' ' && selectable && key !== undefined) {
      e.preventDefault();
      onToggleRow?.(key);
      return true;
    }
    return false;
  };

  // Ctrl+Shift+Left/Right on a header cell resizes its column (ADR-0116); the grid keeps its
  // one tab stop, so this is the keyboard's way to what the border handle does.
  const resizeKey = (e: React.KeyboardEvent, { pos, cell }: KeyTarget): boolean => {
    const column = pos.row === 0 && isResizeKey(e) ? columns[pos.col - firstDataCol] : undefined;
    if (!column) return false;
    e.preventDefault();
    const grow = (e.key === 'ArrowRight') !== isRtl();
    const current = widths[column.id] ?? cell.getBoundingClientRect().width;
    const width = Math.round(Math.max(RESIZE_MIN_WIDTH, current + (grow ? RESIZE_STEP : -RESIZE_STEP)));
    setWidth(column.id, width);
    // Mounted empty first when it is new, like a copy's announcement, so it is read out.
    setAnnouncement((prev) => prev ?? '');
    requestAnimationFrame(() => setAnnouncement(`${column.header} column, ${width} pixels`));
    return true;
  };

  const moveKey = (e: React.KeyboardEvent, pos: GridPos) => {
    if (e.altKey) return;
    const scroll = scrollRef.current;
    const page = Math.max(1, Math.floor(((scroll?.clientHeight ?? ROW_HEIGHT * 10) - headerHeight) / ROW_HEIGHT) - 1);
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

  const onGridKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    const pos = posOf(e.target as Element);
    if (!pos) return;
    const target = e.target as HTMLElement;
    const cell = target.closest<HTMLElement>('[data-grid-col]')!;
    const body = pos.row > 0;
    const at: KeyTarget = {
      pos,
      cell,
      onWidget: target !== cell,
      row: body ? (rows[pos.row - 1]?.original as T | undefined) : undefined,
      key: body ? loadedKeys[pos.row - 1] : undefined,
    };
    if (rowMenuKey(e, at) || escapeKey(e) || copyKey(e, at) || activateKey(e, at) || resizeKey(e, at)) return;
    moveKey(e, pos);
  };

  const toggleMenu = (key: string, trigger: HTMLElement) => {
    if (menu?.key === key) closeMenu(true);
    else openMenu(key, anchorBelow(trigger));
  };

  const activateRow = (e: React.MouseEvent, original: T) => {
    // A control inside the row acts for itself, not for the row.
    if ((e.target as Element).closest(WIDGETS)) return;
    onRowClick?.(original);
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
      target.closest('a[href], input, textarea, select, [contenteditable]') ||
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

  const { template, minInline } = gridTracks(columns, widths, fits, Boolean(selectable), Boolean(rowMenu));

  const sortField = sort?.replace(/^-/, '');
  const sortDesc = sort?.startsWith('-') ?? false;

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

  const actionsCol = colCount - 1;

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
            '--as-cols': template,
            '--as-min-inline': `${minInline}px`,
            '--as-row-h': `${ROW_HEIGHT}px`,
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
          {selectable ? <SelectAllCell keys={loadedKeys} selected={selected} onToggleAll={onToggleAll} /> : null}
          {columns.map((c, i) => (
            <ColumnHeader
              key={c.id}
              column={c}
              col={firstDataCol + i}
              sorting={c.sortKey && onSortChange ? { active: sortField === c.sortKey, desc: sortDesc } : null}
              onSort={() => onSortChange?.(nextSort(c.sortKey!))}
              onResizeStart={(e) => startResize(e, c.id)}
              onFit={() => fitColumn(c.id, firstDataCol + i)}
            />
          ))}
          {rowMenu ? (
            <div role="columnheader" data-grid-col={actionsCol} className={`${styles.cell} ${styles.headCell}`}>
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
                aria-selected={selectable ? (selected?.has(key) ?? false) : undefined}
                data-grid-row={vi.index + 1}
                data-selected={selected?.has(key) || undefined}
                data-menu-open={menu?.key === key || undefined}
                className={`${styles.row} ${styles.bodyRow} ${onRowClick ? styles.clickable : ''} ${rowClassName?.(original) ?? ''}`}
                onClick={onRowClick ? (e) => activateRow(e, original) : undefined}
                style={{ transform: `translateY(${vi.start}px)` }}
              >
                {selectable ? (
                  <SelectCell rowKey={key} checked={selected?.has(key) ?? false} onToggle={onToggleRow} />
                ) : null}
                {columns.map((c, i) => (
                  <DataCell
                    key={c.id}
                    column={c}
                    row={original}
                    col={firstDataCol + i}
                    onReveal={openReveal}
                    onHide={closeReveal}
                  />
                ))}
                {rowMenu ? (
                  <ActionsCell
                    col={actionsCol}
                    label={rowLabel}
                    expanded={menu?.key === key}
                    onToggle={(trigger) => toggleMenu(key, trigger)}
                  />
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

      {reveal ? <RevealPanel reveal={reveal} panelRef={panelRef} onHide={closeReveal} /> : null}
    </div>
  );
}
