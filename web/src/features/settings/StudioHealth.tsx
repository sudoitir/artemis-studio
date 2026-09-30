import { Alert, Badge, Button, Paper, Skeleton, Stack, Table, Text } from '@mantine/core';

import { elapsedLabel, useServerNow } from '../../kernel/time/time.ts';
import { When } from '../../kernel/time/When.tsx';
import {
  useStudioHealth,
  type JobHealth,
  type NodeHealth,
  type PoolHealth,
  type ReplicaHealth,
  type StudioHealth as Health,
} from './api.ts';

const numeric = { fontVariantNumeric: 'tabular-nums' } as const;

const JOB_STATES: Record<JobHealth['status'], string> = {
  OK: 'Last run succeeded',
  FAILING: 'Last run failed',
  NEVER_RUN: 'Not run yet',
};

const REPLICA_STATES: Record<ReplicaHealth['state'], string> = {
  STARTING: 'Starting',
  READY: 'Ready',
  DRAINING: 'Draining: shutting down, taking no new requests',
  STOPPED: 'Stopped',
  GONE: 'Gone: no heartbeat, presumed crashed',
};

/** State goes in words; the colour only underlines a problem. */
function Verdict({ degraded }: Readonly<{ degraded: boolean }>) {
  return (
    // No truncation: the word is the state, so it must always be readable in full.
    <Badge
      variant="light"
      color={degraded ? 'red' : 'gray'}
      styles={{ root: { flexShrink: 0, overflow: 'visible' }, label: { overflow: 'visible' } }}
    >
      {degraded ? 'Degraded' : 'Healthy'}
    </Badge>
  );
}

/** A figure the server could not read is said to be unavailable, never shown as zero. */
function Figure({ value, unit }: Readonly<{ value: number | null | undefined; unit?: string }>) {
  if (value === null || value === undefined) {
    return (
      <Text size="sm" c="dimmed">
        Unavailable
      </Text>
    );
  }
  return (
    <Text size="sm" style={numeric}>
      {value.toLocaleString()}
      {unit ? ` ${unit}` : ''}
    </Text>
  );
}

function Moment({ at, now }: Readonly<{ at: string | null | undefined; now: number }>) {
  if (!at) {
    return (
      <Text size="sm" c="dimmed">
        Never
      </Text>
    );
  }
  return <When at={at} now={now} />;
}

function Lag({ seconds }: Readonly<{ seconds: number | null | undefined }>) {
  if (seconds === null || seconds === undefined) {
    return (
      <Text size="sm" c="dimmed">
        Unavailable
      </Text>
    );
  }
  return (
    <Text size="sm" style={numeric}>
      {seconds === 0 ? 'On schedule' : `${elapsedLabel(seconds * 1000)} behind`}
    </Text>
  );
}

function JobsTable({ jobs, now }: Readonly<{ jobs: JobHealth[]; now: number }>) {
  if (jobs.length === 0) {
    return (
      <Text size="sm">
        No background jobs are registered. Jobs poll the brokers and keep Studio&rsquo;s own data tidy; they appear here
        once their modules start.
      </Text>
    );
  }
  return (
    <Table aria-label="Background jobs">
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Job</Table.Th>
          <Table.Th>Module</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th>Last completed</Table.Th>
          <Table.Th>Lag</Table.Th>
          <Table.Th w="1%">Health</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {jobs.map((j) => (
          <Table.Tr key={j.name}>
            <Table.Td>{j.name}</Table.Td>
            <Table.Td>{j.feature}</Table.Td>
            <Table.Td>{JOB_STATES[j.status]}</Table.Td>
            <Table.Td>
              <Moment at={j.lastEnd} now={now} />
            </Table.Td>
            <Table.Td>
              <Lag seconds={j.lagSeconds} />
            </Table.Td>
            <Table.Td>
              <Verdict degraded={j.degraded} />
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function ReplicasTable({ replicas }: Readonly<{ replicas: ReplicaHealth[] }>) {
  if (replicas.length === 0) {
    return (
      <Text size="sm">
        No replicas have checked in recently. Each Studio process registers itself here once it starts.
      </Text>
    );
  }
  return (
    <Table aria-label="Replicas">
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Replica</Table.Th>
          <Table.Th>Version</Table.Th>
          <Table.Th>State</Table.Th>
          <Table.Th ta="end">Last heartbeat</Table.Th>
          <Table.Th>Clusters owned</Table.Th>
          <Table.Th w="1%">Health</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {replicas.map((r) => (
          <Table.Tr key={r.id}>
            <Table.Td>
              <Text size="sm">
                {r.host}
                {r.self ? (
                  <Badge variant="outline" color="gray" ms="xs">
                    This replica
                  </Badge>
                ) : null}
              </Text>
              <Text size="xs" c="dimmed" style={numeric}>
                {r.id.slice(0, 8)}
              </Text>
            </Table.Td>
            <Table.Td>{r.version}</Table.Td>
            <Table.Td>{REPLICA_STATES[r.state]}</Table.Td>
            <Table.Td ta="end" style={numeric}>
              {elapsedLabel(r.heartbeatAgeMillis)} ago
            </Table.Td>
            <Table.Td>
              {r.ownedClusters.length > 0 ? (
                r.ownedClusters.map((c) => c.name).join(', ')
              ) : (
                <Text size="sm" c="dimmed">
                  None
                </Text>
              )}
            </Table.Td>
            <Table.Td>
              {r.state === 'STOPPED' ? (
                <Text size="sm" c="dimmed">
                  Not running
                </Text>
              ) : (
                <Verdict degraded={r.degraded} />
              )}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function NodesTable({ nodes, now }: Readonly<{ nodes: NodeHealth[]; now: number }>) {
  if (nodes.length === 0) {
    return (
      <Text size="sm">
        No broker nodes are registered, so Studio has made no management calls. Add a cluster and its nodes will be
        measured here.
      </Text>
    );
  }
  return (
    <Table aria-label="Broker nodes">
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Node</Table.Th>
          <Table.Th>Last success</Table.Th>
          <Table.Th>Last failure</Table.Th>
          <Table.Th ta="end">Call latency (p95)</Table.Th>
          <Table.Th ta="end">Rate-limit wait</Table.Th>
          <Table.Th w="1%">Health</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {nodes.map((n) => (
          <Table.Tr key={`${n.clusterId}/${n.name}`}>
            <Table.Td>
              <Text size="sm">{n.name}</Text>
              <Text size="xs" c="dimmed" style={numeric}>
                {n.node ?? 'No management address'}
              </Text>
            </Table.Td>
            <Table.Td>
              <Moment at={n.lastSuccess} now={now} />
            </Table.Td>
            <Table.Td>
              <Moment at={n.lastFailure} now={now} />
              {n.lastError ? (
                <Text size="xs" c="dimmed">
                  {n.lastError}
                </Text>
              ) : null}
            </Table.Td>
            <Table.Td ta="end">
              <Figure value={n.managementP95Millis == null ? null : Math.round(n.managementP95Millis)} unit="ms" />
            </Table.Td>
            <Table.Td ta="end">
              <Figure value={n.rateLimitWaitMillis} unit="ms" />
            </Table.Td>
            <Table.Td>
              <Verdict degraded={n.degraded} />
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function PoolTable({ pool, streamClients }: Readonly<{ pool: PoolHealth; streamClients: number | null | undefined }>) {
  const rows: [string, number | null | undefined][] = [
    ['Database connections in use', pool.active],
    ['Database connections idle', pool.idle],
    ['Database pool size limit', pool.max],
    ['Callers waiting for a connection', pool.pending],
    ['Open event streams', streamClients],
  ];
  return (
    <Table aria-label="Resources">
      <Table.Tbody>
        {rows.map(([label, value]) => (
          <Table.Tr key={label}>
            <Table.Th scope="row" fw={400}>
              {label}
            </Table.Th>
            <Table.Td ta="end">
              <Figure value={value} />
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function Loaded({ health }: Readonly<{ health: Health }>) {
  const now = useServerNow(5_000);
  const answering = health.replicas.find((r) => r.id === health.answeringReplica)?.host;
  return (
    <Stack gap="lg">
      <Paper withBorder p="md">
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          Overall
        </Text>
        <Verdict degraded={health.degraded} />
        <Text size="sm" c="dimmed" mt="xs">
          {health.degraded
            ? 'A job has stalled, a broker node is failing, or a replica is gone or draining; the rows marked Degraded say which.'
            : 'Every job is on schedule and every broker node answered its latest call.'}
        </Text>
      </Paper>
      <Stack gap="xs">
        <Text fw={600}>Replicas</Text>
        <ReplicasTable replicas={health.replicas} />
      </Stack>
      <Stack gap="xs">
        <Text fw={600}>Background jobs</Text>
        <JobsTable jobs={health.jobs} now={now} />
      </Stack>
      <Stack gap="xs">
        <Text fw={600}>Broker nodes</Text>
        <Text size="sm" c="dimmed">
          Call figures are as seen from this replica{answering ? ` (${answering})` : ''}; another replica may see a node
          differently.
        </Text>
        <NodesTable nodes={health.nodes} now={now} />
      </Stack>
      <Stack gap="xs">
        <Text fw={600}>Resources</Text>
        <Text size="sm" c="dimmed">
          The database pool and the open event streams are this replica&rsquo;s own.
        </Text>
        <PoolTable pool={health.dbPool} streamClients={health.streamClients} />
      </Stack>
    </Stack>
  );
}

/** Studio's own health: replicas, jobs, broker calls, the database pool and event streams, polled by the query. */
export function StudioHealth() {
  const health = useStudioHealth();

  if (health.isError) {
    return (
      <Alert color="red" variant="light" title={health.error.title} role="alert">
        <Stack gap="xs" align="flex-start">
          <Text size="sm">{health.error.message} Studio&rsquo;s health could not be loaded; try again.</Text>
          <Button size="xs" variant="default" onClick={() => health.refetch()}>
            Retry
          </Button>
        </Stack>
      </Alert>
    );
  }
  if (health.isPending) {
    return (
      <Stack gap="sm" aria-busy="true" aria-label="Loading Studio health">
        <Skeleton height={72} />
        <Skeleton height={120} />
      </Stack>
    );
  }
  return <Loaded health={health.data} />;
}
