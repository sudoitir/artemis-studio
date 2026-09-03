import { useState } from 'react';
import { Alert, Button, Group, Loader, Stack, Text, Title } from '@mantine/core';
import {
  useCluster,
  useRediscover,
  type NodeEndpointView,
} from '../api/client.ts';
import { PairSpine } from './PairSpine.tsx';
import { CapabilityLedger } from './CapabilityLedger.tsx';
import { AddManagementUrl, RemoveCluster } from './AddManagementUrl.tsx';

export function ClusterDetailPanel({
  clusterId,
  onRemoved,
}: {
  clusterId: string;
  onRemoved: () => void;
}) {
  const { data, isPending, isError, error } = useCluster(clusterId);
  const rediscover = useRediscover(clusterId);
  const [addTarget, setAddTarget] = useState<NodeEndpointView | null>(null);
  const [removing, setRemoving] = useState(false);

  if (isPending) return <Loader size="sm" />;
  if (isError) {
    return (
      <Alert color="red" variant="light" title={error.title}>
        {error.message}
      </Alert>
    );
  }

  const nodeCount = data.topology.nodes.reduce(
    (n, node) => n + node.endpoints.length,
    0,
  );
  const hasPair = data.topology.nodes.some((n) => n.endpoints.length > 1);
  const meta = [
    `${nodeCount} node${nodeCount === 1 ? '' : 's'}`,
    hasPair ? 'replication' : 'standalone',
    data.health.level === 'UNKNOWN' ? 'not yet contacted' : 'reachable',
  ].join(' · ');

  return (
    <Stack gap="lg" maw={760}>
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
          <Button
            variant="default"
            size="xs"
            color="red"
            onClick={() => setRemoving(true)}
          >
            Remove
          </Button>
        </Group>
      </Group>

      <HealthBanner
        level={data.health.level}
        critical={data.health.splitBrain === 'CRITICAL'}
        notes={data.health.notes}
      />

      {data.topology.nodes.map((node) => (
        <PairSpine
          key={node.artemisNodeId ?? node.endpoints[0]?.id}
          node={node}
          onAddManagementUrl={setAddTarget}
        />
      ))}

      <div>
        <Title order={4} mb="xs">
          What this connection can do
        </Title>
        <CapabilityLedger capabilities={data.capabilities} />
      </div>

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
        onRemoved={onRemoved}
      />
    </Stack>
  );
}

function HealthBanner({
  level,
  critical,
  notes,
}: {
  level: string;
  critical: boolean;
  notes: string[];
}) {
  if (level === 'OK' || notes.length === 0) return null;
  return (
    <Alert
      color={critical ? 'red' : 'yellow'}
      variant="light"
      role={critical ? 'alert' : undefined}
      title={critical ? 'Two nodes are live in one pair' : 'Needs attention'}
    >
      <Stack gap={4}>
        {notes.map((n) => (
          <Text key={n} size="sm">
            {n}
          </Text>
        ))}
      </Stack>
    </Alert>
  );
}
