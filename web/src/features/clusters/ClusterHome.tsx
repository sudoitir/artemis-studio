import { Loader } from '@mantine/core';
import { Navigate } from '@tanstack/react-router';

import { useClusters } from './api.ts';
import { EmptyState } from './RegisterCluster.tsx';

/** The landing page (`home.empty`): the first cluster, or the prompt to register one. */
export function ClusterHome() {
  const clusters = useClusters();

  if (!clusters.data) return <Loader size="sm" />;
  if (clusters.data.length === 0) return <EmptyState />;
  return <Navigate to={`/clusters/${clusters.data[0].id}`} replace />;
}
