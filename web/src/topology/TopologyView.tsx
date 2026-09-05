import { Skeleton, Stack } from '@mantine/core';
import { useParams } from '@tanstack/react-router';

import { useTopology } from '../api/client.ts';
import { TopologyGraph } from './TopologyGraph.tsx';
import styles from './TopologyGraph.module.css';

/** The topology route: the cross-node graph (React Flow), the cluster's landing view. */
export function TopologyView() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const topology = useTopology(clusterId);

  // A placeholder the size of the graph, not a small spinner in a tall void.
  if (topology.isPending) {
    return <Skeleton className={styles.wrapper} animate />;
  }

  return (
    <Stack gap="md">
      <TopologyGraph clusterId={clusterId} />
    </Stack>
  );
}
