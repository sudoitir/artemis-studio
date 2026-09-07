import { useCallback, useEffect, useRef, useState } from 'react';

import {
  ApiError,
  type SqlNodeOutcomeView,
  type SqlResultView,
  type SqlRowView,
  type SqlTailStatusView,
} from '../api/client.ts';

/**
 * How long a newly arrived row is marked fresh. Long enough to catch the eye on a
 * glance away from the screen, short enough that a busy tail is not one solid
 * block of highlight.
 */
const FRESH_MS = 4_000;

/** A row's identity is `(node, queue, messageId)` — ids are node-local. */
export function rowKey(row: SqlRowView): string {
  return `${row.nodeId}/${row.queueName}/${row.messageId}`;
}

/**
 * What the console is doing.
 *
 * - `running` — the static query is fanning out; rows are arriving.
 * - `done` — it finished and nothing more is coming.
 * - `tailing` — it finished and the tail is polling.
 * - `failed` — the server refused or could not run it; `error` says why.
 * - `disconnected` — the stream dropped. The query is *not* silently restarted:
 *   reconnecting would re-run a fan-out the operator did not ask for a second
 *   time, and would write a second audit record for one intent.
 */
export type RunStatus = 'idle' | 'running' | 'done' | 'tailing' | 'failed' | 'disconnected';

export interface SqlRun {
  rows: SqlRowView[];
  /** Rows that arrived in the last few seconds, for the live highlight. */
  freshKeys: ReadonlySet<string>;
  /** The finished static query: outcomes, bounds, notices and the plan that ran. */
  result: SqlResultView | null;
  /** Per-node outcomes as they land, before `result` exists. */
  progress: SqlNodeOutcomeView[];
  tail: SqlTailStatusView | null;
  error: ApiError | null;
  status: RunStatus;
  start: (sql: string, tail: boolean) => void;
  stop: () => void;
}

interface Request {
  sql: string;
  tail: boolean;
  /** Bumped on every Run, so running the same text again really re-runs it. */
  nonce: number;
}

/**
 * The console's one execution path: a stream per query (ADR-0058).
 *
 * <p>The static run and the tail arrive on the same connection, so progress,
 * per-node outcomes and cancellation are one mechanism rather than two that can
 * disagree. Closing the stream is what stops the query — the server's sink reports
 * itself cancelled and issues no further broker read — which is why the effect's
 * cleanup closes it rather than leaving it to be garbage collected.
 *
 * <p>A refusal arrives as a `failed` frame carrying the same problem body the JSON
 * API would have returned, because an `EventSource` cannot read the body of a
 * non-200 and a refusal without its estimate is not actionable.
 */
export function useSqlTail(clusterId: string): SqlRun {
  const [request, setRequest] = useState<Request | null>(null);
  const [rows, setRows] = useState<SqlRowView[]>([]);
  const [freshKeys, setFreshKeys] = useState<ReadonlySet<string>>(new Set());
  const [result, setResult] = useState<SqlResultView | null>(null);
  const [progress, setProgress] = useState<SqlNodeOutcomeView[]>([]);
  const [tail, setTail] = useState<SqlTailStatusView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [status, setStatus] = useState<RunStatus>('idle');

  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  const start = useCallback((sql: string, wantsTail: boolean) => {
    setRequest((prev) => ({ sql, tail: wantsTail, nonce: (prev?.nonce ?? 0) + 1 }));
  }, []);

  const stop = useCallback(() => {
    setRequest(null);
    // The query itself finished; only the tail was stopped. Reporting it as idle
    // would throw away a result the operator is still reading.
    setStatus((prev) => (prev === 'tailing' ? 'done' : 'idle'));
  }, []);

  useEffect(() => {
    if (!request) return;

    setRows([]);
    setFreshKeys(new Set());
    setResult(null);
    setProgress([]);
    setTail(null);
    setError(null);
    setStatus('running');

    // True once the server has said its piece. The close that follows is then the
    // end of a finished stream, not a dropped one.
    let settled = false;
    let tailing = false;

    const source = new EventSource(
      `/api/v1/clusters/${clusterId}/sql/stream?sql=${encodeURIComponent(request.sql)}&tail=${request.tail}`,
    );

    const parse = <T,>(e: Event): T | null => {
      try {
        return JSON.parse((e as MessageEvent).data) as T;
      } catch {
        return null;
      }
    };

    source.addEventListener('row', (e) => {
      const row = parse<SqlRowView>(e);
      if (!row) return;
      const key = rowKey(row);
      // While tailing, the newest row goes to the top: an operator watching a live
      // feed is watching the head of it, not scrolling to find it.
      setRows((prev) => (tailing ? [row, ...prev] : [...prev, row]));
      if (tailing) {
        setFreshKeys((prev) => new Set(prev).add(key));
        timers.current.push(
          setTimeout(() => {
            setFreshKeys((prev) => {
              const next = new Set(prev);
              next.delete(key);
              return next;
            });
          }, FRESH_MS),
        );
      }
    });

    source.addEventListener('node', (e) => {
      const node = parse<SqlNodeOutcomeView>(e);
      if (node) setProgress((prev) => [...prev, node]);
    });

    source.addEventListener('done', (e) => {
      const done = parse<SqlResultView>(e);
      if (done) setResult(done);
      if (request.tail) {
        tailing = true;
        setStatus('tailing');
      } else {
        settled = true;
        setStatus('done');
      }
    });

    source.addEventListener('tail', (e) => {
      const status = parse<SqlTailStatusView>(e);
      if (status) setTail(status);
    });

    source.addEventListener('failed', (e) => {
      // A refusal the server sent. Deliberately not named `error`: an EventSource
      // dispatches its own connection failures under that name, and the two must
      // not be confused for one another.
      const problem = parse<Record<string, unknown>>(e);
      if (!problem) return;
      settled = true;
      setError(new ApiError(typeof problem.status === 'number' ? problem.status : 500, problem));
      setStatus('failed');
    });

    source.onerror = () => {
      source.close();
      if (!settled) setStatus('disconnected');
    };

    return () => {
      source.close();
      timers.current.forEach(clearTimeout);
      timers.current = [];
    };
  }, [clusterId, request]);

  return { rows, freshKeys, result, progress, tail, error, status, start, stop };
}
