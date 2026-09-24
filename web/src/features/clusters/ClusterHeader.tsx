import { useState } from 'react';
import { Alert, Button, Group, Loader, Stack, Text, Title } from '@mantine/core';

import { useDismissedNotice } from '../../kernel/useDismissedNotice.ts';
import { useTitlePart } from '../../kernel/shell/pageTitle.ts';
import { RemoveCluster } from './AddManagementUrl.tsx';
import { useCluster, useRediscover } from './api.ts';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import styles from './ClusterHeader.module.css';

/**
 * Above every view of a cluster (`cluster.header`): its identity, the health banner, and a notice
 * for a capability the connection lacks.
 */
export function ClusterHeader({ clusterId }: { clusterId: string }) {
  const { data, isPending, isError, error } = useCluster(clusterId);
  const rediscover = useRediscover(clusterId);
  // The cluster's name, for the shell's title and breadcrumb (ADR-0109).
  useTitlePart('cluster', data?.name);
  const [removing, setRemoving] = useState(false);

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
    <>
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

      <RemoveCluster
        clusterId={clusterId}
        clusterName={data.name}
        opened={removing}
        onClose={() => setRemoving(false)}
        onRemoved={() => window.history.back()}
      />
    </>
  );
}
