import { useEffect, useSyncExternalStore } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';

import { heldOperationsKey } from '../api/request.ts';
import { inboxKeys } from '../inbox/api.ts';
import { backoff, EVICTED, OFFLINE_AFTER, PING, RECONNECT, RESYNC, SILENCE_MS } from './sse.ts';
import type { StreamStatus } from './useClusterStream.ts';

/** A notice was posted to, read or dismissed for this user (`UserSignals.INBOX`). */
const INBOX = 'inbox';
/** One of this user's held operations, or one they may decide, changed (`UserSignals.HELD`). */
const HELD = 'held';

/** Which queries each signal makes stale. A frame carries no data: the client refetches, the bus never carries the truth. */
const STALE_ON: Readonly<Record<string, readonly (readonly unknown[])[]>> = {
  [INBOX]: [inboxKeys.all],
  [HELD]: [heldOperationsKey],
  [RESYNC]: [inboxKeys.all, heldOperationsKey],
};

/**
 * One connection per tab, shared by every mounted user of the hook: the server allows a user only a
 * few streams at once, and two copies of the shell's bell must not spend two of them.
 */
const clients = new Map<symbol, QueryClient>();
let close: (() => void) | null = null;

let status: StreamStatus | null = null;
const listeners = new Set<() => void>();

function publish(next: StreamStatus | null) {
  if (status === next) return;
  status = next;
  for (const l of listeners) l();
}

function invalidate(keys: readonly (readonly unknown[])[]) {
  for (const qc of new Set(clients.values())) {
    for (const queryKey of keys) void qc.invalidateQueries({ queryKey });
  }
}

/** Opens the stream and keeps it open until the returned function is called. Mirrors `useClusterStream`. */
function open(): () => void {
  publish('connecting');
  let failures = 0;
  let refetchOnOpen = false;
  let closed = false;
  let source: EventSource | null = null;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let watchdog: ReturnType<typeof setTimeout> | undefined;
  let resume: (() => void) | null = null;

  const heard = () => {
    if (watchdog) clearTimeout(watchdog);
    watchdog = setTimeout(() => {
      // No frame for the whole window. The socket may look open; it is not.
      source?.close();
      fail();
    }, SILENCE_MS);
  };

  const fail = () => {
    if (closed) return;
    failures += 1;
    refetchOnOpen = true;
    publish(failures >= OFFLINE_AFTER ? 'offline' : 'reconnecting');
    retry = setTimeout(connect, backoff(failures));
  };

  const stopWaiting = () => {
    if (!resume) return;
    document.removeEventListener('visibilitychange', resume);
    window.removeEventListener('focus', resume);
    resume = null;
  };

  const connect = () => {
    if (closed) return;
    source = new EventSource('/api/v1/me/stream');
    heard();

    source.onopen = () => {
      failures = 0;
      publish('live');
      heard();
      // Signals sent while the connection was down are not replayed: catch up on everything they cover.
      if (refetchOnOpen) {
        refetchOnOpen = false;
        invalidate(STALE_ON[RESYNC]);
      }
    };

    source.addEventListener(RECONNECT, () => {
      // Not a failure: the server asked. No backoff, and the status is left as it is.
      source?.close();
      failures = 0;
      refetchOnOpen = true;
      connect();
    });

    source.addEventListener(EVICTED, () => {
      // A newer tab took this one's place. Views poll while the stream is not live; reconnect once the tab is used.
      source?.close();
      if (watchdog) clearTimeout(watchdog);
      publish('offline');
      resume = () => {
        if (document.visibilityState !== 'visible') return;
        stopWaiting();
        refetchOnOpen = true;
        connect();
      };
      document.addEventListener('visibilitychange', resume);
      window.addEventListener('focus', resume);
    });

    source.addEventListener(PING, heard);

    for (const [event, keys] of Object.entries(STALE_ON)) {
      source.addEventListener(event, () => {
        heard();
        invalidate(keys);
      });
    }

    source.onerror = () => {
      source?.close();
      if (watchdog) clearTimeout(watchdog);
      fail();
    };
  };

  connect();
  return () => {
    closed = true;
    stopWaiting();
    if (retry) clearTimeout(retry);
    if (watchdog) clearTimeout(watchdog);
    source?.close();
    publish(null);
  };
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * The signed-in user's own event stream (`GET /api/v1/me/stream`): their inbox and their held operations,
 * on every page rather than only a cluster's. Each signal invalidates the queries it covers, and a
 * `resync`, or a reconnect after a failure, refetches all of them. It reconnects indefinitely with capped,
 * jittered backoff and treats silence as failure, as the cluster stream does. A stream the server closed for
 * a newer tab (`evicted`) waits until this tab is in use again.
 *
 * Returns the connection's state, so a view can poll while it is not `live`.
 */
export function useUserStream(): StreamStatus {
  const qc = useQueryClient();
  useEffect(() => {
    const owner = Symbol('user-stream');
    clients.set(owner, qc);
    close ??= open();
    return () => {
      clients.delete(owner);
      if (clients.size === 0) {
        close?.();
        close = null;
      }
    };
  }, [qc]);
  return useSyncExternalStore(
    subscribe,
    () => status ?? 'connecting',
    () => 'connecting',
  );
}
