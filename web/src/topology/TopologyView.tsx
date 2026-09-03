import { Loader, Stack } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { useTopology } from '../api/client.ts';
import { TopologyGraph } from './TopologyGraph.tsx';

/** The topology route: the cross-node graph (React Flow), the cluster's landing view. */
export function TopologyView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const topology = useTopology(clusterId);

  if (topology.isPending) return <Loader size="sm" />;

  return (
    <Stack gap="md">
      <TopologyGraph clusterId={clusterId} />
    </Stack>
  );
}
