import { useEffect, useRef, useSyncExternalStore } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';

import { offerPing } from '../app/time.ts';
import type { TopicHandler } from '../kernel/feature.ts';
import { useFeatures } from '../kernel/features.ts';
import { isPollingPaused, markPendingChange } from './polling.ts';

/** What the UI reports about the live connection (ADR-0052). */
export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

/** The topics the cluster layout keeps open; a view adds its own by mounting a stream. */
export const DEFAULT_TOPICS = ['topology', 'health', 'queues'];

/** The server's keep-alive (`SseHub.PING`). Not a topic — every subscriber gets it. */
const PING = 'ping';

const BACKOFF_FLOOR_MS = 1_000;
const BACKOFF_CAP_MS = 30_000;
/**
 * A connection with no frame for this long is treated as dead. Comfortably above
 * the server's keep-alive interval so one missed beat is not read as death; if
 * `sse.heartbeat-interval` is raised past a third of this, raise this with it.
 */
const SILENCE_MS = 45_000;
/** Failures past this read as "the server is gone", not "the connection blipped". */
const OFFLINE_AFTER = 3;

/**
 * The stream's state as a module-level store rather than a React context.
 *
 * The freshness indicator lives in the application header, which renders *above*
 * the cluster route that mounts the stream — a provider could never reach it
 * without hoisting the mount out of `ClusterLayout` and changing where the stream
 * belongs (ADR-0018). A store sidesteps the ordering entirely, and matches the
 * pause signal in `polling.ts`.
 *
 * `null` means no stream is mounted, which is the truth on every non-cluster
 * route and is rendered as "no live stream here", not as an error.
 */
let current: StreamStatus | null = null;
const statusListeners = new Set<() => void>();

function publish(next: StreamStatus | null) {
  if (current === next) return;
  current = next;
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

/** Capped exponential backoff with full jitter, so a restart is not stampeded. */
function backoff(failures: number): number {
  const ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_FLOOR_MS * 2 ** (failures - 1));
  return BACKOFF_FLOOR_MS + Math.random() * (ceiling - BACKOFF_FLOOR_MS);
}

/**
 * While refreshing is paused a signal must not refetch, but it must not be lost
 * either: mark the data stale and record that something is waiting, so the
 * freshness bar can say so instead of the screen quietly going out of date.
 */
function invalidate(qc: QueryClient, queryKey: readonly unknown[]) {
  if (isPollingPaused()) {
    markPendingChange();
    qc.invalidateQueries({ queryKey, refetchType: 'none' });
    return;
  }
  qc.invalidateQueries({ queryKey });
}

/**
 * One `EventSource` per mounted cluster view (ADR-0003, ADR-0018, ADR-0027).
 *
 * - Each topic's frames go to the handler its feature contributes (ADR-0070). A
 *   signal topic's handler invalidates the matching TanStack Query keys and the
 *   normal `queryFn` refetches; a topic of a disabled feature has no handler.
 * - `onFrame` also hands the view every frame of the topics it mounted, for a topic
 *   that carries data with no server-side resource behind it, such as the live
 *   broker-event feed. The browser echoes the last `id:` back as `Last-Event-ID`
 *   on reconnect, so missed events replay automatically.
 *
 * It reconnects indefinitely with capped exponential backoff and full jitter, and
 * treats silence as failure (ADR-0052): an intermediary that drops the connection
 * without a clean close fires no `error`, so the only way to notice is to miss a
 * keep-alive. The returned status is what the freshness indicator reports; the
 * per-hook `refetchInterval` keeps every view updating while it is not `live`.
 */
export function useClusterStream(
  clusterId: string,
  topics: string[] = DEFAULT_TOPICS,
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
    const wanted = topicKey.split(',');
    publish('connecting');
    let failures = 0;
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
      publish(failures >= OFFLINE_AFTER ? 'offline' : 'reconnecting');
      retry = setTimeout(connect, backoff(failures));
    };

    const connect = () => {
      if (closed) return;
      source = new EventSource(`/api/v1/stream?clusterId=${clusterId}&topics=${topicKey}`);
      heard();

      source.onopen = () => {
        failures = 0;
        publish('live');
        heard();
      };

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
          const data = (e as MessageEvent).data as string;
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
      publish(null);
    };
  }, [clusterId, topicKey, qc, onFrame]);

  return status ?? 'connecting';
}
