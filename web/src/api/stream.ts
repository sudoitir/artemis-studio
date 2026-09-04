import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';

import { keys, type BrokerEventView } from './client.ts';

export type Topic =
  | 'topology'
  | 'health'
  | 'queues'
  | 'events'
  | 'consumers'
  | 'sessions'
  | 'connections';

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
 * Two consecutive failures ⇒ stop reconnecting and rely on the 5s
 * `refetchInterval` every cluster hook already carries.
 */
export function useClusterStream(
  clusterId: string,
  topics: Topic[] = DEFAULT_TOPICS,
  onEvent?: (event: BrokerEventView) => void,
): void {
  const qc = useQueryClient();
  const topicKey = topics.join(',');

  useEffect(() => {
    const wanted = topicKey.split(',') as Topic[];
    let failures = 0;
    let source: EventSource | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;

    const connect = () => {
      source = new EventSource(
        `/api/v1/stream?clusterId=${clusterId}&topics=${topicKey}`,
      );
      source.onopen = () => {
        failures = 0;
      };
      for (const topic of wanted) {
        if (topic === 'events') {
          source.addEventListener('events', (e) => {
            try {
              onEvent?.(JSON.parse((e as MessageEvent).data) as BrokerEventView);
            } catch {
              /* malformed frame — ignore */
            }
          });
        } else if (SIGNAL_TOPICS.includes(topic)) {
          const signalTopic = topic as Exclude<Topic, 'events'>;
          source.addEventListener(signalTopic, () => {
            qc.invalidateQueries({ queryKey: keys.topic(clusterId, signalTopic) });
          });
        }
      }
      source.onerror = () => {
        source?.close();
        failures += 1;
        if (failures >= 2) return; // give up; polling takes over
        retry = setTimeout(connect, 1_000 * failures);
      };
    };

    connect();
    return () => {
      if (retry) clearTimeout(retry);
      source?.close();
    };
  }, [clusterId, topicKey, qc, onEvent]);
}
