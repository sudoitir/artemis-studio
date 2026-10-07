import type { ReactNode } from 'react';
import { Text } from '@mantine/core';

import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { useCluster } from './api.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import { ConnectionForm } from './ConnectionSettings.tsx';
import { RegisterClusterButton } from './RegisterClusterButton.tsx';

/** Settings section: register another cluster. */
export function RegisterSection() {
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        Register another cluster; remove this one under This cluster → Remove cluster. Studio finds brokers that join a
        registered cluster on its own.
      </Text>
      <RegisterClusterButton />
    </>
  );
}

/** A section's content once the cluster has loaded; until then a frame of its size, so nothing below moves. */
function SectionBody({
  cluster,
  blockSize,
  children,
}: Readonly<{
  cluster: ReturnType<typeof useCluster>;
  /** The height of the content that replaces the frame. */
  blockSize: string;
  children: (data: NonNullable<ReturnType<typeof useCluster>['data']>) => ReactNode;
}>) {
  if (cluster.data) return <>{children(cluster.data)}</>;
  if (cluster.isError) return <ErrorState error={cluster.error} onRetry={() => void cluster.refetch()} />;
  return <LoadingState label="Loading the cluster" blockSize={blockSize} />;
}

/** Settings section: how Studio connects to this cluster, checked before it is saved. */
export function ConnectionSection({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        How Studio reaches <strong>{cluster.data?.name ?? 'this cluster'}</strong>: the management address you gave, the
        pattern that gives every other node its own, and the management and Core accounts, which are stored separately.
        Check a change first; nothing is saved until you save it.
      </Text>
      <SectionBody cluster={cluster} blockSize="24rem">
        {(data) =>
          data.connection ? (
            <ConnectionForm cluster={data} connection={data.connection} />
          ) : (
            <Text size="sm">You can see this cluster only through a team, so its connection is not shown.</Text>
          )
        }
      </SectionBody>
    </>
  );
}

/** Settings section: what this connection can and cannot do. */
export function CapabilitiesSection({ clusterId }: Readonly<{ clusterId: string }>) {
  const cluster = useCluster(clusterId);
  return (
    <>
      <Text size="sm" c="dimmed" mb="sm">
        What this connection can and cannot do over Jolokia. Rows that are not plainly available expand with the reason
        and the exact <code>broker.xml</code> change to close the gap.
      </Text>
      <SectionBody cluster={cluster} blockSize="14rem">
        {(data) => <CapabilityLedger capabilities={data.capabilities} clusterId={clusterId} />}
      </SectionBody>
    </>
  );
}
