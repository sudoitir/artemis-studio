import { useSyncExternalStore } from 'react';
import type { Query, QueryClient } from '@tanstack/react-query';

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
 *
 * ponytail: the interval is re-resolved when the *current* timer fires, so one
 * further poll can land up to `ms` after pausing. Closing that gap means
 * cancelling in-flight fetches, which throws away work the operator did not ask
 * to discard and can leave a screen mid-update. Accepted and recorded in
 * ADR-0055; revisit only if an operator can actually observe it.
 */
export function poll(ms: number | false): () => number | false {
  return () => (paused ? false : ms);
}

/**
 * `refetchOnMount` for the QueryClient default, so pausing covers opening a view
 * and not only the intervals.
 *
 * Pausing intervals but not mounts means an operator who pauses and then
 * navigates has silently unpaused — every query the new screen observes is stale
 * (the stream marks them so) and refetches on mount.
 *
 * A query that has never resolved is exempt: suspending its first fetch would hand
 * the operator an empty screen. They paused a screen showing data to stop it
 * moving, not to stop data existing.
 */
export function mountRefetch(): (query: Query) => boolean {
  return (query) => !paused || query.state.dataUpdatedAt === 0;
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
 * Refetch everything the current screen is observing, and resolve when it is done.
 *
 * `refetchType: 'active'` is the point: it refetches what is on the display and
 * leaves the rest of the cache alone — the same scope the freshness indicator
 * reports on, so the button and the label can never disagree.
 *
 * `cancelRefetch: false` is the other point. TanStack's default aborts the
 * in-flight fetch and starts another, which is right after a mutation (the
 * in-flight response is known-stale) and wrong for a refresh control (the
 * in-flight response is exactly what was asked for). With it, a second activation
 * joins the first instead of restarting it.
 *
 * The returned promise is what lets the control show *the operator's* refresh
 * rather than every background poll.
 */
export function refreshActiveQueries(qc: QueryClient): Promise<void> {
  return qc.invalidateQueries({ refetchType: 'active' }, { cancelRefetch: false });
}
