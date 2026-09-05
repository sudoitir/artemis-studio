import { useState } from 'react';
import { Alert, Button, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { Outlet, useParams } from '@tanstack/react-router';

import styles from './ClusterLayout.module.css';

import { useCluster, useRediscover } from '../api/client.ts';
import { useClusterStream } from '../api/stream.ts';
import { RemoveCluster } from '../clusters/AddManagementUrl.tsx';
import { CapabilityLedger } from '../clusters/CapabilityLedger.tsx';

/**
 * One cluster's screen: identity header, the health banner, and the routed
 * view. The per-cluster view nav lives in the sidebar now (ADR-0034), not a
 * strip here. Mounts the SSE stream for this cluster so the topology graph and
 * queue grid patch live (falls back to the 5s poll on two failures).
 */
export function ClusterLayout() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const { data, isPending, isError, error } = useCluster(clusterId);
  const rediscover = useRediscover(clusterId);
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
  const capsNeedingSetup =
    (['managementRead', 'managementWrite', 'messageIo'] as const).some(
      (k) => data.capabilities[k].status !== 'AVAILABLE',
    ) ||
    // notifications: nag only on a real, actionable gap — not while it is still
    // UNKNOWN because the first scrape cycle has not run.
    data.capabilities.notifications.status === 'UNAVAILABLE';

  return (
    <Stack gap="lg">
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <div className={styles.identity}>
          <Title order={1} fz="h2">
            {data.name}
          </Title>
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

      <Outlet />

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
