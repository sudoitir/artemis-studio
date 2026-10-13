import { cellText } from './measurement.ts';
import type { Column } from './columns.ts';

/** The zero-based positions of the rows with a shown cell that contains the query, ignoring case. */
export function findRows<T>(columns: readonly Column<T>[], data: readonly T[], query: string): number[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') return [];
  const matches: number[] = [];
  for (let i = 0; i < data.length; i++) {
    if (columns.some((column) => cellText(column.accessor(data[i])).toLowerCase().includes(needle))) matches.push(i);
  }
  return matches;
}

/** The match after or before the current one, wrapping at both ends; -1 where there are none. */
export function stepMatch(current: number, count: number, by: 1 | -1): number {
  if (count === 0) return -1;
  return (current + by + count) % count;
}

/** What the find says about its result, for the eye and for a screen reader. */
export function findSummary(query: string, count: number, current: number): string {
  if (query.trim() === '') return '';
  if (count === 0) return 'No matches';
  return `${current + 1} of ${count} ${count === 1 ? 'match' : 'matches'}`;
}
