import { useEffect } from 'react';
import type { SpotlightActionGroupData } from '@mantine/spotlight';
import { useNavigate } from '@tanstack/react-router';

import { useQueues } from './api.ts';

/** The command palette's Queues group: the open cluster's queues by name, each opening the filtered queue list. */
export function QueuePalette({
  clusterId,
  report,
}: {
  clusterId?: string;
  report: (groups: SpotlightActionGroupData[]) => void;
}) {
  const queues = useQueues(clusterId ?? '', {});
  const navigate = useNavigate();

  useEffect(() => {
    if (!clusterId || !queues.data) {
      report([]);
      return;
    }
    report([
      {
        group: 'Queues',
        actions: queues.data.data.slice(0, 40).map((q) => ({
          id: `queue-${q.address}-${q.queueName}`,
          label: `${q.address} / ${q.queueName}`,
          description: `depth ${q.totalMessageCount} · ${q.nodesPresent}/${q.nodesTotal} nodes`,
          onClick: () => navigate({ to: `/clusters/${clusterId}/queues`, search: { q: q.queueName } }),
        })),
      },
    ]);
  }, [clusterId, queues.data, navigate, report]);

  return null;
}
