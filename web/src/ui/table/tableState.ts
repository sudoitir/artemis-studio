import { useCallback, useState } from 'react';

/**
 * What a viewer has done to one table (ADR-0160): the widths they set, the columns they hid or chose
 * to show, and their order. Stored per browser, never in the URL. It is the shape a saved view would
 * persist.
 */
export interface TableState {
  v: 1;
  /** Widths in px a viewer set. Each is a hard track: fitting never shrinks it. */
  widths: Record<string, number>;
  /** Columns the viewer hid. */
  hidden: string[];
  /** Columns the viewer chose to show, which the solver never hides for lack of room. */
  shown: string[];
  /** Column ids in the order the viewer wants; ids it does not list follow in their own order. */
  order: string[];
}

export const EMPTY_TABLE_STATE: TableState = { v: 1, widths: {}, hidden: [], shown: [], order: [] };

/** The narrowest a viewer can make a column. */
export const MIN_COLUMN_WIDTH = 48;
const MAX_COLUMN_WIDTH = 4000;

const storageName = (storageKey: string) => `as.table.${storageKey}`;

function idList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : [];
}

/** The state a stored string holds, or the empty one when it is missing, not JSON, or from another shape. */
export function parseTableState(raw: string | null): TableState {
  try {
    const value: unknown = JSON.parse(raw ?? 'null');
    if (!value || typeof value !== 'object' || (value as { v?: unknown }).v !== 1) return EMPTY_TABLE_STATE;
    const { widths, hidden, shown, order } = value as Record<string, unknown>;
    const sane: Record<string, number> = {};
    if (widths && typeof widths === 'object') {
      for (const [id, width] of Object.entries(widths)) {
        if (
          typeof width === 'number' &&
          Number.isFinite(width) &&
          width >= MIN_COLUMN_WIDTH &&
          width <= MAX_COLUMN_WIDTH
        ) {
          sane[id] = Math.round(width);
        }
      }
    }
    return { v: 1, widths: sane, hidden: idList(hidden), shown: idList(shown), order: idList(order) };
  } catch {
    return EMPTY_TABLE_STATE;
  }
}

/** The stored state for a table, or the empty one when it has no key or storage is unavailable. */
export function readTableState(storageKey: string | undefined): TableState {
  if (!storageKey) return EMPTY_TABLE_STATE;
  try {
    return parseTableState(localStorage.getItem(storageName(storageKey)));
  } catch {
    return EMPTY_TABLE_STATE;
  }
}

function writeTableState(storageKey: string | undefined, state: TableState) {
  if (!storageKey) return;
  try {
    localStorage.setItem(storageName(storageKey), JSON.stringify(state));
  } catch {
    // Storage full or blocked: the state still holds for this visit.
  }
}

/** The state with `id` set to `width` px, within what a column can be. */
export function withWidth(state: TableState, id: string, width: number): TableState {
  const clamped = Math.round(Math.min(Math.max(width, MIN_COLUMN_WIDTH), MAX_COLUMN_WIDTH));
  return { ...state, widths: { ...state.widths, [id]: clamped } };
}

/** The state with `id` back to fitting. */
export function withoutWidth(state: TableState, id: string): TableState {
  return { ...state, widths: Object.fromEntries(Object.entries(state.widths).filter(([other]) => other !== id)) };
}

/** The state with every width back to fitting. */
export function withoutWidths(state: TableState): TableState {
  return { ...state, widths: {} };
}

/** The state with `id` shown (and kept shown) or hidden. */
export function withVisibility(state: TableState, id: string, visible: boolean): TableState {
  const others = (ids: string[]) => ids.filter((other) => other !== id);
  return visible
    ? { ...state, hidden: others(state.hidden), shown: [...others(state.shown), id] }
    : { ...state, shown: others(state.shown), hidden: [...others(state.hidden), id] };
}

/**
 * The columns in the order a viewer chose: those it lists first, in its order, then the rest as
 * declared. An id the table no longer has is ignored.
 */
export function orderColumns<C extends { id: string }>(columns: C[], order: string[]): C[] {
  if (order.length === 0) return columns;
  const byId = new Map(columns.map((column) => [column.id, column]));
  const listed = order.flatMap((id) => byId.get(id) ?? []);
  return [...listed, ...columns.filter((column) => !order.includes(column.id))];
}

/**
 * A table's state, read from `as.table.<storageKey>` and read again whenever the key changes (a view
 * that serves several resource kinds keeps each kind's widths apart). Without a key the state lasts
 * as long as the table is shown. `update` writes through to storage.
 */
export function useTableState(
  storageKey: string | undefined,
): [TableState, (change: (state: TableState) => TableState) => void] {
  const [held, setHeld] = useState(() => ({ key: storageKey, state: readTableState(storageKey) }));
  let current = held;
  if (held.key !== storageKey) {
    current = { key: storageKey, state: readTableState(storageKey) };
    setHeld(current);
  }
  const update = useCallback((change: (state: TableState) => TableState) => {
    setHeld((prev) => {
      const state = change(prev.state);
      writeTableState(prev.key, state);
      return { key: prev.key, state };
    });
  }, []);
  return [current.state, update];
}
