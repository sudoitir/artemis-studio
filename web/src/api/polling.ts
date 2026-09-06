import { useSyncExternalStore } from 'react';
import type { QueryClient } from '@tanstack/react-query';

/**
 * The one seam that makes every periodic refetch pausable (ADR-0052).
 *
 * `refetchInterval` accepts a function, so a module-level signal plus {@link poll}
 * converts fifteen literal intervals into pausable ones without per-hook state and
 * without a re-render whenever the flag changes — TanStack Query re-reads the
 * function itself on each cycle.
 *
 * Deliberately memory-only. A persisted pause outlives the reason someone set it,
 * and the first thing an operator does with a screen they distrust is reload it.
 */

let paused = false;
/** Set when a live signal arrived while paused, so the UI can say data is waiting. */
let pending = false;

const listeners = new Set<() => void>();

function notify() {
  for (const l of listeners) l();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * `refetchInterval` for a hook that should stop polling while paused.
 *
 * Returning `false` suspends the interval; TanStack Query calls this again on the
 * next cycle, so resuming needs no remount.
 */
export function poll(ms: number): () => number | false {
  return () => (paused ? false : ms);
}

export function isPollingPaused(): boolean {
  return paused;
}

export function setPollingPaused(next: boolean): void {
  if (paused === next) return;
  paused = next;
  // Resuming clears the backlog marker: whatever was waiting is about to be
  // fetched, so leaving it set would report stale data on a live screen.
  if (!next) pending = false;
  notify();
}

/** Record that the server said something changed while refreshing was paused. */
export function markPendingChange(): void {
  if (!paused || pending) return;
  pending = true;
  notify();
}

export function usePollingPaused(): boolean {
  return useSyncExternalStore(subscribe, isPollingPaused, isPollingPaused);
}

export function usePendingChange(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => pending,
    () => false,
  );
}

/**
 * Refetch everything the current screen is observing.
 *
 * `refetchType: 'active'` is the point: it refetches what is on the display and
 * leaves the rest of the cache alone — the same scope the freshness indicator
 * reports on, so the button and the label can never disagree.
 */
export function refreshActiveQueries(qc: QueryClient): void {
  void qc.invalidateQueries({ refetchType: 'active' });
}
