import { useEffect, useSyncExternalStore } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';

import { offerPing } from '../app/time.ts';
import { keys, type BrokerEventView } from './client.ts';
import { isPollingPaused, markPendingChange } from './polling.ts';

export type Topic =
  | 'topology'
  | 'health'
  | 'queues'
  | 'events'
  | 'consumers'
  | 'sessions'
  | 'connections'
  | 'rr'
  | 'alerts'
  | 'config';

/** What the UI reports about the live connection (ADR-0052). */
export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

const DEFAULT_TOPICS: Topic[] = ['topology', 'health', 'queues'];

/** Signal topics invalidate a query key; `events` carries data and has no key. */
const SIGNAL_TOPICS: Topic[] = [
  'topology',
  'health',
  'queues',
  'consumers',
  'sessions',
  'connections',
];

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
 * - Signal topics are change signals, not data: each invalidates the matching
 *   TanStack Query key and the normal `queryFn` refetches.
 * - The `events` topic carries the full broker-event payload; there is no
 *   server-side resource behind a live feed, so it is handed to `onEvent`
 *   instead of invalidating. The browser echoes the last `id:` back as
 *   `Last-Event-ID` on reconnect, so missed events replay automatically.
 *
 * It reconnects indefinitely with capped exponential backoff and full jitter, and
 * treats silence as failure (ADR-0052): an intermediary that drops the connection
 * without a clean close fires no `error`, so the only way to notice is to miss a
 * keep-alive. The returned status is what the freshness indicator reports; the
 * per-hook `refetchInterval` keeps every view updating while it is not `live`.
 */
export function useClusterStream(
  clusterId: string,
  topics: Topic[] = DEFAULT_TOPICS,
  onEvent?: (event: BrokerEventView) => void,
): StreamStatus {
  const qc = useQueryClient();
  const topicKey = topics.join(',');
  const status = useStreamStatus();

  useEffect(() => {
    const wanted = topicKey.split(',') as Topic[];
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
        if (topic === 'events') {
          source.addEventListener('events', (e) => {
            heard();
            try {
              onEvent?.(JSON.parse((e as MessageEvent).data) as BrokerEventView);
            } catch {
              /* malformed frame — ignore */
            }
          });
        } else if (topic === 'rr') {
          source.addEventListener('rr', () => {
            heard();
            invalidate(qc, ['clusters', clusterId, 'rr']);
          });
        } else if (topic === 'alerts') {
          source.addEventListener('alerts', () => {
            heard();
            invalidate(qc, ['clusters', clusterId, 'alerts']);
            invalidate(qc, ['alerts', 'firing']);
          });
        } else if (topic === 'config') {
          // A drift evaluation or an apply finished; the declaration view carries
          // both, so one key covers the tabs.
          source.addEventListener('config', () => {
            heard();
            invalidate(qc, keys.brokerConfig(clusterId));
          });
        } else if (SIGNAL_TOPICS.includes(topic)) {
          const signalTopic = topic as Exclude<Topic, 'events' | 'rr' | 'alerts' | 'config'>;
          source.addEventListener(signalTopic, () => {
            heard();
            invalidate(qc, keys.topic(clusterId, signalTopic));
          });
        }
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
  }, [clusterId, topicKey, qc, onEvent]);

  return status ?? 'connecting';
}
