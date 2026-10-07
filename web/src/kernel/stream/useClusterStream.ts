import { useEffect, useRef, useSyncExternalStore } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';

import { offerPing } from '../time/time.ts';
import type { TopicHandler } from '../feature.ts';
import { useFeatures } from '../features.ts';
import { isPollingPaused, markPendingChange } from '../api/polling.ts';
import { clusterKey } from '../api/request.ts';
import { backoff, OFFLINE_AFTER, PING, RECONNECT, RESYNC, SILENCE_MS } from './sse.ts';

/** What the UI reports about the live connection (ADR-0052). */
export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

/**
 * The stream's state as a module-level store rather than a React context.
 *
 * The freshness indicator lives in the application header, which renders *above*
 * the cluster route that mounts the stream — a provider could never reach it
 * without hoisting the mount out of `ClusterLayout` and changing where the stream
 * belongs (ADR-0018). A store sidesteps the ordering entirely, and matches the
 * pause signal in `polling.ts`.
 *
 * Every mounted stream owns one entry, and the store reports the worst of them: the cluster's
 * stream and a view's own live feed can be mounted together, and one tearing down must not
 * clear, or hide a failure of, the other. `null` means no stream is mounted, which is the truth
 * on every non-cluster route and is rendered as "no live stream here", not as an error.
 */
const SEVERITY: Record<StreamStatus, number> = { live: 0, connecting: 1, reconnecting: 2, offline: 3 };
const owned = new Map<symbol, StreamStatus>();
let current: StreamStatus | null = null;
const statusListeners = new Set<() => void>();

/** Set one stream's status, or drop it with `null`, and notify when the worst of them changed. */
function publish(owner: symbol, next: StreamStatus | null) {
  if (next === null) owned.delete(owner);
  else owned.set(owner, next);
  let worst: StreamStatus | null = null;
  for (const status of owned.values()) {
    if (worst === null || SEVERITY[status] > SEVERITY[worst]) worst = status;
  }
  if (current === worst) return;
  current = worst;
  for (const l of statusListeners) l();
}

/** The live-stream state, or `null` on a route that mounts no stream. */
export function useStreamStatus(): StreamStatus | null {
  return useSyncExternalStore(
    (listener) => {
      statusListeners.add(listener);
      return () => {
        statusListeners.delete(listener);
      };
    },
    () => current,
    () => null,
  );
}

/**
 * While refreshing is paused a signal must not refetch, but it must not be lost
 * either: mark the data stale and record that something is waiting, so the
 * freshness bar can say so instead of the screen quietly going out of date.
 */
function invalidate(qc: QueryClient, queryKey: readonly unknown[]) {
  if (isPollingPaused()) {
    markPendingChange();
    void qc.invalidateQueries({ queryKey, refetchType: 'none' });
    return;
  }
  void qc.invalidateQueries({ queryKey });
}

/**
 * One `EventSource` per mounted cluster view (ADR-0003, ADR-0018, ADR-0027).
 *
 * - Each topic's frames go to the handler its feature contributes (ADR-0070). A
 *   signal topic's handler invalidates the matching TanStack Query keys and the
 *   normal `queryFn` refetches; a topic of a disabled feature has no handler.
 * - `onFrame` also hands the view every frame of the topics it mounted, for a topic
 *   that carries data with no server-side resource behind it, such as the live
 *   broker-event feed. A new `EventSource` never sends `Last-Event-ID` itself, so the
 *   hook remembers the last frame id and presents it as `lastEventId` on every connect,
 *   on whichever replica it lands; the server replays what was missed.
 * - `reconnect` (the server is draining) reopens at once with the backoff reset and is
 *   not a failure. `resync`, or the first successful connect after a failure, refetches
 *   every query of the cluster: change signals sent while the client was away are not
 *   replayed.
 *
 * It reconnects indefinitely with capped exponential backoff and full jitter, and
 * treats silence as failure (ADR-0052): an intermediary that drops the connection
 * without a clean close fires no `error`, so the only way to notice is to miss a
 * keep-alive. The returned status is what the freshness indicator reports; the
 * per-hook `refetchInterval` keeps every view updating while it is not `live`.
 */
export function useClusterStream(
  clusterId: string,
  topics: string[],
  onFrame?: (topic: string, data: string) => void,
): StreamStatus {
  const qc = useQueryClient();
  const topicKey = topics.join(',');
  const status = useStreamStatus();

  // Read when a frame arrives rather than subscribed to: the feature list is rebuilt
  // on every render, and re-subscribing on each would reconnect the stream.
  const features = useFeatures();
  const handlers = useRef<Record<string, TopicHandler>>({});
  useEffect(() => {
    handlers.current = Object.assign({}, ...features.map((feature) => feature.streamTopics ?? {}));
  });

  useEffect(() => {
    // A view that asks for no topics opens no stream; the server would answer with its defaults.
    if (topicKey === '') return;
    const wanted = topicKey.split(',');
    const owner = Symbol('cluster-stream');
    publish(owner, 'connecting');
    let failures = 0;
    let lastEventId = '';
    let refetchOnOpen = false;
    let closed = false;
    let source: EventSource | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let watchdog: ReturnType<typeof setTimeout> | undefined;

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
      publish(owner, failures >= OFFLINE_AFTER ? 'offline' : 'reconnecting');
      retry = setTimeout(connect, backoff(failures));
    };

    const connect = () => {
      if (closed) return;
      const resume = lastEventId ? `&lastEventId=${encodeURIComponent(lastEventId)}` : '';
      source = new EventSource(`/api/v1/stream?clusterId=${clusterId}&topics=${topicKey}${resume}`);
      heard();

      source.onopen = () => {
        failures = 0;
        publish(owner, 'live');
        heard();
        if (refetchOnOpen) {
          refetchOnOpen = false;
          invalidate(qc, clusterKey(clusterId));
        }
      };

      source.addEventListener(RECONNECT, () => {
        // Not a failure: the server asked. No backoff, and the status is left as it is.
        source?.close();
        failures = 0;
        connect();
      });

      source.addEventListener(RESYNC, () => {
        heard();
        invalidate(qc, clusterKey(clusterId));
      });

      // Every frame is evidence the connection is alive, whatever it carries. The
      // keep-alive carries one thing more: `SseHub.heartbeat` puts the server's
      // clock in it, so the client is told the time every twenty seconds for free.
      // It is a drift detector only — a ping cannot measure its own latency, so it
      // must never teach the offset estimate (`app/time.ts`).
      source.addEventListener(PING, (e) => {
        heard();
        offerPing((e as MessageEvent).data);
      });

      for (const topic of wanted) {
        source.addEventListener(topic, (e) => {
          heard();
          const { data, lastEventId: id } = e as MessageEvent<string>;
          if (id) lastEventId = id;
          handlers.current[topic]?.({ clusterId, data, invalidate: (queryKey) => invalidate(qc, queryKey) });
          onFrame?.(topic, data);
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
      if (retry) clearTimeout(retry);
      if (watchdog) clearTimeout(watchdog);
      source?.close();
      publish(owner, null);
    };
  }, [clusterId, topicKey, qc, onFrame]);

  return status ?? 'connecting';
}
