import { useEffect, useRef, useState } from 'react';

import { entryFor, readHistory, recordHistory, type HistoryEntry } from './queryHistory.ts';
import type { SqlRun } from './useSqlTail.ts';

/**
 * The query history. A run that reached its result is recorded once, keyed on the run's own number, with
 * the SQL that ran and what it returned: not a keystroke, and not the text now in the editor, which the
 * operator may have changed while the query read. A reconnect re-runs the same run, so it is not recorded
 * twice, and a run cancelled before it had a result is not recorded at all.
 */
export function useQueryHistory({ result, rows, runId, sql }: SqlRun) {
  const [history, setHistory] = useState<HistoryEntry[]>(readHistory);
  const recorded = useRef(0);
  useEffect(() => {
    if (!result || recorded.current === runId) return;
    recorded.current = runId;
    setHistory(recordHistory(entryFor(sql, rows, result.plan?.source)));
  }, [result, rows, runId, sql]);
  return [history, setHistory] as const;
}
