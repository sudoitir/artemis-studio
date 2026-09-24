import { useEffect } from 'react';
import { useNavigate } from '@tanstack/react-router';

import type { PaletteSource } from '../../kernel/feature.ts';
import { useQueues } from './api.ts';

const SHOWN = 8;

/**
 * The command palette's Queues group (ADR-0109): the queues whose name or address matches what is
 * typed, each opening that queue. It reads Studio's own queue snapshot — never a broker — and only
 * while the palette is open with at least two characters typed, once per query rather than polling.
 */
export const QueuePalette: PaletteSource = ({ clusterId, query, opened, report }) => {
  const searching = Boolean(clusterId) && opened && query.length >= 2;
  const queues = useQueues(clusterId ?? '', { q: query, size: SHOWN }, { enabled: searching, live: false });
  const navigate = useNavigate();

  useEffect(() => {
    if (!searching || !clusterId || !queues.data || queues.isPlaceholderData) {
      report([]);
      return;
    }
    const { data, count } = queues.data;
    report([
      {
        group: 'Queues',
        actions: [
          ...data.map((q) => ({
            id: `queue-${q.address}-${q.queueName}`,
            label: q.queueName,
            description: `${q.address === q.queueName ? '' : `on ${q.address} · `}depth ${q.totalMessageCount.toLocaleString()} · ${q.nodesPresent}/${q.nodesTotal} nodes`,
            // The query itself, so the palette's own filter keeps what the server already matched.
            keywords: [query, q.address],
            onClick: () => navigate({ to: `/clusters/${clusterId}/queues`, search: { queue: q.queueName } }),
          })),
          ...(count > data.length
            ? [
                {
                  id: 'queues-all',
                  label: `Show all ${count.toLocaleString()} queues matching "${query}"`,
                  keywords: [query],
                  onClick: () => navigate({ to: `/clusters/${clusterId}/queues`, search: { q: query } }),
                },
              ]
            : []),
        ],
      },
    ]);
  }, [searching, clusterId, query, queues.data, queues.isPlaceholderData, navigate, report]);

  return null;
};
