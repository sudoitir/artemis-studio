import { useSyncExternalStore } from 'react';
import { focusManager, type Query, type QueryClient } from '@tanstack/react-query';

/**
 * The one seam that pauses automatic refreshing for every query (ADR-0052, ADR-0118).
 *
 * Every TanStack observer checks `focusManager.isFocused()` before an interval tick
 * fetches, so reporting "not focused" while paused stops every interval at its next
 * tick — a literal one, a plugin's, one written next year — with nothing asked of the
 * hook that declared it. Opt-in wrapping was tried first and leaked on every hook
 * that forgot it.
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
 * Make TanStack's focus state mean "may refresh on its own": the tab is visible and
 * refreshing is not paused. Called once, beside the `QueryClient`.
 *
 * A side effect: the retryer also waits for focus, so a request that fails while
 * paused retries on resume rather than during the pause.
 */
export function installPauseSeam(): void {
  // TanStack calls the previous listener's cleanup when a new one is set, so
  // installing again (a test harness does) never stacks listeners.
  focusManager.setEventListener((setFocused) => {
    const update = () => setFocused(!paused && document.visibilityState !== 'hidden');
    globalThis.addEventListener('visibilitychange', update, false);
    const unsubscribe = subscribe(update);
    update();
    return () => {
      globalThis.removeEventListener('visibilitychange', update);
      unsubscribe();
    };
  });
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
 * Refetch everything the current screen is observing: what resuming does, because the
 * first thing an operator needs after unpausing is current data.
 *
 * `refetchType: 'active'` scopes it to what is on the display, the same scope the
 * freshness indicator reports on. `cancelRefetch: false` lets a fetch already in
 * flight finish rather than aborting it for an identical one.
 */
export function refreshActiveQueries(qc: QueryClient): Promise<void> {
  return qc.invalidateQueries({ refetchType: 'active' }, { cancelRefetch: false });
}
