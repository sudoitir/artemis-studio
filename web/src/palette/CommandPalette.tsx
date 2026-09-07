import { useMemo } from 'react';
import { Spotlight, type SpotlightActionGroupData } from '@mantine/spotlight';
import { useNavigate, useParams } from '@tanstack/react-router';
import { useQueryClient } from '@tanstack/react-query';

import { useClusters, useQueues } from '../api/client.ts';
import {
  isPollingPaused,
  refreshActiveQueries,
  setPollingPaused,
  usePollingPaused,
} from '../api/polling.ts';
import { NAV_ITEMS } from '../app/navItems.ts';

/**
 * ⌘K navigation across the console: jump to a cluster, a view, or a queue by
 * name. Mounted once in the root layout; the shortcut is registered by
 * {@link Spotlight}.
 *
 * Refresh and pause live here rather than on a hotkey: the browser owns both
 * shortcuts an operator would reach for (⌘R and ⇧⌘R), and taking either would be
 * worse than not having one (ADR-0052).
 */
export function CommandPalette() {
  const navigate = useNavigate();
  const clusters = useClusters();
  const params = useParams({ strict: false }) as { clusterId?: string };
  const clusterId = params.clusterId;
  const queues = useQueues(clusterId ?? '', {});
  const qc = useQueryClient();
  const paused = usePollingPaused();

  const groups = useMemo<SpotlightActionGroupData[]>(() => {
    const out: SpotlightActionGroupData[] = [
      {
        group: 'Data',
        actions: [
          {
            id: 'refresh-data',
            label: 'Refresh data',
            description: 'Refetch everything on this screen',
            onClick: () => void refreshActiveQueries(qc),
          },
          {
            id: 'toggle-auto-refresh',
            label: paused ? 'Resume auto-refresh' : 'Pause auto-refresh',
            description: paused
              ? 'Start refetching on the usual interval again'
              : 'Stop refetching until you resume; does not survive a reload',
            onClick: () => setPollingPaused(!isPollingPaused()),
          },
        ],
      },
    ];

    if (clusterId) {
      out.push({
        group: 'Go to view',
        actions: NAV_ITEMS.map((item) => ({
          id: `view-${item.path}`,
          label: item.label,
          onClick: () => navigate({ to: `/clusters/${clusterId}/${item.path}` }),
        })),
      });
    }

    out.push({
      group: 'Clusters',
      actions: (clusters.data ?? []).map((c) => ({
        id: `cluster-${c.id}`,
        label: c.name,
        description: `${c.nodeCount} node${c.nodeCount === 1 ? '' : 's'}`,
        onClick: () => navigate({ to: `/clusters/${c.id}/topology` }),
      })),
    });

    if (clusterId && queues.data) {
      out.push({
        group: 'Queues',
        actions: queues.data.data.slice(0, 40).map((q) => ({
          id: `queue-${q.address}-${q.queueName}`,
          label: `${q.address} / ${q.queueName}`,
          description: `depth ${q.totalMessageCount} · ${q.nodesPresent}/${q.nodesTotal} nodes`,
          onClick: () =>
            navigate({
              to: `/clusters/${clusterId}/queues`,
              search: { q: q.queueName },
            }),
        })),
      });
    }

    return out;
  }, [clusterId, clusters.data, queues.data, navigate, qc, paused]);

  return (
    <Spotlight
      actions={groups}
      shortcut={['mod + K']}
      nothingFound="Nothing matches"
      highlightQuery
      searchProps={{ placeholder: 'Jump to a cluster, view, or queue…' }}
    />
  );
}
