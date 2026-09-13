import { useState } from 'react';
import { Alert, Button, Group, Loader, Stack, Text, Title } from '@mantine/core';
import { Outlet, useParams } from '@tanstack/react-router';

import styles from './ClusterLayout.module.css';

import { useCluster, useRediscover } from '../api/client.ts';
import { DEFAULT_TOPICS, useClusterStream } from '../api/stream.ts';
import { RemoveCluster } from '../clusters/AddManagementUrl.tsx';
import { CapabilityLedger } from '../clusters/CapabilityLedger.tsx';
import { useDismissedNotice } from './useDismissedNotice.ts';

/**
 * One cluster's screen: identity header, the health banner, and the routed
 * view. The per-cluster view nav lives in the sidebar now (ADR-0034), not a
 * strip here. Mounts the SSE stream for this cluster so the topology graph and
 * queue grid patch live. The stream reconnects indefinitely and publishes its
 * state to the header's freshness indicator (ADR-0052); while it is down the
 * per-hook refetch intervals keep every view updating.
 */
export function ClusterLayout() {
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const { data, isPending, isError, error } = useCluster(clusterId);
  const rediscover = useRediscover(clusterId);
  const [removing, setRemoving] = useState(false);

  // `config` rides along with the default topics rather than being mounted by the
  // configuration screen: a second EventSource would open a second connection and
  // fight over the shared stream-status store. The server only emits on this topic
  // when an apply or a drift evaluation finishes, and an invalidation of a key no
  // mounted query holds costs nothing.
  useClusterStream(clusterId, [...DEFAULT_TOPICS, 'config']);

  const caps = data?.capabilities;
  // Nag only on a real, actionable gap. UNKNOWN is not one: since ADR-0049 D5
  // managementWrite and messageIo stay UNKNOWN until a write has actually been
  // attempted, and a notice on every freshly registered cluster — for a broker
  // that is very likely fine — is noise the operator learns to dismiss unread.
  const gaps = caps
    ? ([
        ...(['managementRead', 'managementWrite', 'messageIo', 'notifications'] as const).filter(
          (k) => caps[k].status === 'UNAVAILABLE',
        ),
      ] as string[])
    : [];
  // Keyed on which capabilities are short, so dismissing today's gap does not
  // also hide a different one that appears tomorrow. Computed before the early
  // returns below so the hook order never depends on the query state.
  const [capsDismissed, dismissCaps] = useDismissedNotice(
    `capabilities:${clusterId}:${gaps.join(',')}`,
  );

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

      {gaps.length > 0 && !capsDismissed ? (
        <Alert
          color="gray"
          variant="light"
          title="Some broker capabilities need setup"
          withCloseButton
          closeButtonLabel="Dismiss until you sign out"
          onClose={dismissCaps}
        >
          <Stack gap="xs">
            <Text size="sm">
              One or more features are limited by this connection. Each row below expands with the
              reason and the <code>broker.xml</code> change that closes the gap.
            </Text>
            <CapabilityLedger capabilities={data.capabilities} clusterId={clusterId} />
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
