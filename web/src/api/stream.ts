import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';

import { keys } from './client.ts';

type Topic = 'topology' | 'health' | 'queues';
const DEFAULT_TOPICS: Topic[] = ['topology', 'health', 'queues'];

/**
 * One `EventSource` per mounted cluster view (ADR-0003, ADR-0018). Each named
 * event is a change signal, not data: it invalidates the matching TanStack
 * Query key and the normal `queryFn` refetches. Two consecutive failures ⇒ stop
 * reconnecting and rely on the 5s `refetchInterval` that every cluster hook
 * already carries — so "fall back to polling" is literally "stop streaming".
 */
export function useClusterStream(
  clusterId: string,
  topics: Topic[] = DEFAULT_TOPICS,
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
        source.addEventListener(topic, () => {
          qc.invalidateQueries({ queryKey: keys.topic(clusterId, topic) });
        });
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
  }, [clusterId, topicKey, qc]);
}
