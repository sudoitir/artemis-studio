import { Navigate } from '@tanstack/react-router';

import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { useClusters } from './api.ts';
import { RegisterClusterForm } from './RegisterCluster.tsx';

/**
 * The landing page's content (`home.empty`): the first cluster, or the prompt to register one. `HomeView`
 * owns the page and its h1, so this holds none.
 */
export function ClusterHome() {
  const clusters = useClusters();

  if (clusters.data && clusters.data.length > 0) {
    return <Navigate to={`/clusters/${clusters.data[0].id}`} replace />;
  }
  return (
    <>
      {clusters.isPending ? <LoadingState label="Loading clusters" blockSize="24rem" /> : null}
      {clusters.isError ? <ErrorState error={clusters.error} onRetry={() => void clusters.refetch()} /> : null}
      {clusters.data ? (
        <>
          <EmptyState
            kind="empty"
            title="No clusters yet"
            description="A cluster is a set of Artemis brokers that Studio watches and manages together. Register the first one below; it appears here and in the cluster switcher once it is saved."
          />
          <Section title="Register a cluster">
            <RegisterClusterForm />
          </Section>
        </>
      ) : null}
    </>
  );
}
