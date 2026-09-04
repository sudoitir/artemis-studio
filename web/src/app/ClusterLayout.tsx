import { useState } from 'react';
import { Alert, Button, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { Link, Outlet, useParams } from '@tanstack/react-router';

import styles from './ClusterLayout.module.css';

import { useCluster, useRediscover, type NodeEndpointView } from '../api/client.ts';
import { useClusterStream } from '../api/stream.ts';
import { AddManagementUrl, RemoveCluster } from '../clusters/AddManagementUrl.tsx';
import { CapabilityLedger } from '../clusters/CapabilityLedger.tsx';

const VIEWS = [
  'topology',
  'queues',
  'addresses',
  'consumers',
  'sessions',
  'connections',
  'producers',
  'settings',
] as const;

/**
 * One cluster's screen: identity header, the health banner, a view strip, and
 * the routed view. Mounts the SSE stream for this cluster so the topology graph
 * and queue grid patch live (falls back to the 5s poll on two failures).
 */
export function ClusterLayout() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const { data, isPending, isError, error } = useCluster(clusterId);
  const rediscover = useRediscover(clusterId);
  const [addTarget, setAddTarget] = useState<NodeEndpointView | null>(null);
  const [removing, setRemoving] = useState(false);

  useClusterStream(clusterId);

  if (isPending) return <Loader size="sm" />;
  if (isError) {
    return (
      <Alert color="red" variant="light" title={error.title}>
        {error.message}
      </Alert>
    );
  }

  const nodeCount = data.topology.nodes.reduce((n, node) => n + node.endpoints.length, 0);
  const hasPair = data.topology.nodes.some((n) => n.endpoints.length > 1);
  const meta = [
    `${nodeCount} node${nodeCount === 1 ? '' : 's'}`,
    hasPair ? 'replication' : 'standalone',
    data.health.level === 'UNKNOWN' ? 'not yet contacted' : 'reachable',
  ].join(' · ');
  const critical = data.health.splitBrain === 'CRITICAL';
  // notifications is UNKNOWN until Phase 4 by design — don't nag about it here.
  const capsNeedingSetup = (['managementRead', 'managementWrite', 'messageIo'] as const).some(
    (k) => data.capabilities[k].status !== 'AVAILABLE',
  );

  return (
    <Stack gap="lg">
      <Group justify="space-between" align="flex-start">
        <div>
          <Title order={2}>{data.name}</Title>
          <Text size="sm" c="dimmed">
            {meta}
          </Text>
        </div>
        <Group gap="xs">
          <Button
            variant="default"
            size="xs"
            loading={rediscover.isPending}
            onClick={() => rediscover.mutate()}
          >
            Check
          </Button>
          <Button variant="default" size="xs" color="red" onClick={() => setRemoving(true)}>
            Remove
          </Button>
        </Group>
      </Group>

      {data.health.level !== 'OK' && data.health.notes.length > 0 ? (
        <Alert
          color={critical ? 'red' : 'yellow'}
          variant="light"
          role={critical ? 'alert' : undefined}
          title={critical ? 'Two nodes are live in one pair' : 'Needs attention'}
        >
          <Stack gap={4}>
            {data.health.notes.map((n) => (
              <Text key={n} size="sm">
                {n}
              </Text>
            ))}
          </Stack>
        </Alert>
      ) : null}

      {capsNeedingSetup ? (
        <Alert color="gray" variant="light" title="Some broker capabilities need setup">
          <Stack gap="xs">
            <Text size="sm">
              One or more features are limited by this connection. Each row below expands with the
              reason and the <code>broker.xml</code> change that closes the gap.
            </Text>
            <CapabilityLedger capabilities={data.capabilities} />
          </Stack>
        </Alert>
      ) : null}

      <nav className={styles.viewStrip} aria-label="Cluster views">
        {VIEWS.map((v) => (
          <Link
            key={v}
            to={`/clusters/${clusterId}/${v}`}
            className={styles.viewTab}
            activeProps={{ 'data-active': 'true' }}
          >
            {v[0].toUpperCase() + v.slice(1)}
          </Link>
        ))}
      </nav>

      <Outlet />

      <AddManagementUrl
        clusterId={clusterId}
        endpoint={addTarget}
        opened={addTarget !== null}
        onClose={() => setAddTarget(null)}
      />
      <RemoveCluster
        clusterId={clusterId}
        clusterName={data.name}
        opened={removing}
        onClose={() => setRemoving(false)}
        onRemoved={() => window.history.back()}
      />
    </Stack>
  );
}

export { VIEWS };
