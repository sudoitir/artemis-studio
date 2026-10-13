import { useDeferredValue, useEffect, useMemo, useState } from 'react';

import type { Column } from './columns.ts';
import { findRows } from './gridFind.ts';

/** The find bar's state; `epoch` changes each time it is opened, so its input is focused anew. */
export interface FindState {
  query: string;
  cursor: number;
  epoch: number;
}

/**
 * The grid's own find (the browser's cannot see rows the virtualizer has not rendered): the bar's state,
 * the matching rows, and the row the find stands on, which `scrollTo` brings into view.
 */
export function useGridFind<T>(columns: readonly Column<T>[], data: readonly T[], scrollTo: (row: number) => void) {
  const [find, setFind] = useState<FindState | null>(null);
  const query = useDeferredValue(find?.query ?? '');
  const matches = useMemo(() => findRows(columns, data, query), [columns, data, query]);
  const cursor = matches.length === 0 ? -1 : Math.min(find?.cursor ?? 0, matches.length - 1);
  const foundRow = find && cursor >= 0 ? matches[cursor] : -1;
  useEffect(() => {
    if (foundRow >= 0) scrollTo(foundRow);
  }, [foundRow, scrollTo]);
  return { find, setFind, matches, cursor, foundRow };
}
