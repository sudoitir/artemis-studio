import { Loader } from '@mantine/core';
import { Navigate } from '@tanstack/react-router';

import { useClusters } from '../api/client.ts';
import { EmptyState } from '../clusters/RegisterCluster.tsx';

/** Landing: bounce to the first cluster, or show the register prompt. */
export function HomeView() {
  const clusters = useClusters();

  if (!clusters.data) return <Loader size="sm" />;
  if (clusters.data.length === 0) return <EmptyState />;
  return <Navigate to={`/clusters/${clusters.data[0].id}`} replace />;
}
