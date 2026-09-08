import type { SqlRowView } from "../api/client.ts";

/**
 * The queries this operator has run on this browser (7.7).
 *
 * <p>Deliberately local. A saved *view* is a shared, named artefact an estate keeps
 * — that is the roadmap's separate feature, with a table and permissions behind it.
 * This is the far more common need: the query from ten minutes ago, before the
 * incident got worse. It is per-viewer convenience, so `localStorage` is the right
 * home and losing it costs nothing.
 *
 * <p>Every read and write is guarded: a private window, cleared site data, or a
 * browser configured to block storage all make the accessor itself throw, and the
 * console must render exactly the same without it.
 */

const KEY = "artemis-studio.sql.history";

/** Enough to cover an incident, small enough to stay inside a storage quota. */
const MAX_ENTRIES = 40;

export interface HistoryEntry {
  sql: string;
  /** ISO instant the query was run. */
  at: string;
  rowCount: number;
  /** BROKER or INDEX — the same provenance the result carries. */
  source?: string;
}

export function readHistory(): HistoryEntry[] {
  try {
    const raw = window.localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as HistoryEntry[]) : [];
  } catch {
    return [];
  }
}

/**
 * Record a completed run. The same text run twice moves to the top rather than
 * appearing twice — a history of one repeated query is not a history.
 */
export function recordHistory(entry: HistoryEntry): HistoryEntry[] {
  const next = [
    entry,
    ...readHistory().filter((e) => e.sql !== entry.sql),
  ].slice(0, MAX_ENTRIES);
  try {
    window.localStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    // Storage is unavailable or full. The console works without history.
  }
  return next;
}

export function clearHistory(): HistoryEntry[] {
  try {
    window.localStorage.removeItem(KEY);
  } catch {
    // Nothing to clear, or nowhere to clear it from.
  }
  return [];
}

/** What a finished run contributes to the history. */
export function entryFor(
  sql: string,
  rows: SqlRowView[],
  source?: string,
): HistoryEntry {
  return { sql, at: new Date().toISOString(), rowCount: rows.length, source };
}
