import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';

/** What the header can say about the data on the screen right now (ADR-0052). */
export interface Freshness {
  /** Newest successful fetch among the queries this screen is observing, in epoch ms. */
  lastUpdatedAt: number | null;
  isFetching: boolean;
  /** At least one query the screen depends on is in error. */
  hasError: boolean;
  /** How many queries the screen is observing. Zero means there is nothing to report. */
  observed: number;
}

const EMPTY: Freshness = { lastUpdatedAt: null, isFetching: false, hasError: false, observed: 0 };

function same(a: Freshness, b: Freshness): boolean {
  return (
    a.lastUpdatedAt === b.lastUpdatedAt &&
    a.isFetching === b.isFetching &&
    a.hasError === b.hasError &&
    a.observed === b.observed
  );
}

/**
 * Freshness derived from the query cache rather than wired per screen.
 *
 * The queries with **active observers** are exactly the ones the screen on the
 * display depends on, so no screen can forget to opt in and a screen added later
 * is covered the day it is written. A query nobody is watching has no freshness
 * worth reporting.
 *
 * `lastUpdatedAt` is the *newest* success, not the oldest: the oldest is
 * pessimistic in a misleading way — one slow background query would make a live
 * screen read as stale — and the honesty lives in `hasError` instead, which puts
 * the whole indicator into its offline state rather than letting a fresh sibling
 * paper over a failing one.
 */
export function useFreshness(): Freshness {
  const qc = useQueryClient();
  const [state, setState] = useState<Freshness>(EMPTY);

  useEffect(() => {
    const cache = qc.getQueryCache();

    const read = (): Freshness => {
      let lastUpdatedAt: number | null = null;
      let isFetching = false;
      let hasError = false;
      let observed = 0;

      for (const query of cache.getAll()) {
        if (query.getObserversCount() === 0) continue;
        observed += 1;
        const s = query.state;
        if (s.dataUpdatedAt > 0 && (lastUpdatedAt === null || s.dataUpdatedAt > lastUpdatedAt)) {
          lastUpdatedAt = s.dataUpdatedAt;
        }
        if (s.fetchStatus === 'fetching') isFetching = true;
        if (s.status === 'error') hasError = true;
      }
      return { lastUpdatedAt, isFetching, hasError, observed };
    };

    // One walk per cache event, not two. The cache notifies on every query state
    // transition, and this walks every query in it — at a few hundred observed
    // queries the second read was pure waste on the hottest path in the shell.
    const sync = () => {
      const next = read();
      setState((prev) => (same(prev, next) ? prev : next));
    };

    sync();
    return cache.subscribe(sync);
  }, [qc]);

  return state;
}

/**
 * A clock that ticks only to re-render a relative label.
 *
 * Separate from {@link useFreshness} because the two change for different
 * reasons: the freshness state changes when data arrives, the label changes
 * because time passed. Merging them would re-render the bar every second even
 * when nothing about the data moved.
 */
export function useNow(intervalMs = 1_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}

/** `4s`, `3m`, `2h` — short enough to sit in a header without wrapping. */
export function elapsedLabel(ms: number): string {
  const seconds = Math.max(0, Math.round(ms / 1_000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.round(hours / 24)}d`;
}
