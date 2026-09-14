import { useEffect } from 'react';
import type { SpotlightActionGroupData } from '@mantine/spotlight';
import { useNavigate } from '@tanstack/react-router';

import { useClusters } from './api.ts';

/** The command palette's Clusters group: every registered cluster, opening on its topology. */
export function ClusterPalette({ report }: { report: (groups: SpotlightActionGroupData[]) => void }) {
  const clusters = useClusters();
  const navigate = useNavigate();

  useEffect(() => {
    report([
      {
        group: 'Clusters',
        actions: (clusters.data ?? []).map((c) => ({
          id: `cluster-${c.id}`,
          label: c.name,
          description: `${c.nodeCount} node${c.nodeCount === 1 ? '' : 's'}`,
          onClick: () => navigate({ to: `/clusters/${c.id}/topology` }),
        })),
      },
    ]);
  }, [clusters.data, navigate, report]);

  return null;
}
