import { useMemo } from 'react';
import { Spotlight, type SpotlightActionGroupData } from '@mantine/spotlight';
import { useNavigate, useParams } from '@tanstack/react-router';

import { useClusters, useQueues } from '../api/client.ts';

const VIEWS = [
  'topology',
  'queues',
  'addresses',
  'consumers',
  'sessions',
  'connections',
  'producers',
  'dlq',
  'audit',
  'settings',
] as const;

/**
 * ⌘K navigation across the console: jump to a cluster, a view, or a queue by
 * name. Mounted once in the root layout; the shortcut is registered by
 * {@link Spotlight}.
 */
export function CommandPalette() {
  const navigate = useNavigate();
  const clusters = useClusters();
  const params = useParams({ strict: false }) as { clusterId?: string };
  const clusterId = params.clusterId;
  const queues = useQueues(clusterId ?? '', {});

  const groups = useMemo<SpotlightActionGroupData[]>(() => {
    const out: SpotlightActionGroupData[] = [];

    if (clusterId) {
      out.push({
        group: 'Go to view',
        actions: VIEWS.map((v) => ({
          id: `view-${v}`,
          label: v[0].toUpperCase() + v.slice(1),
          onClick: () => navigate({ to: `/clusters/${clusterId}/${v}` }),
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
  }, [clusterId, clusters.data, queues.data, navigate]);

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
