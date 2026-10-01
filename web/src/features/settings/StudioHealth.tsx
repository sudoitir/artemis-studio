import { Stack } from '@mantine/core';

import { useServerNow } from '../../kernel/time/time.ts';
import { EmptyState } from '../../ui/EmptyState.tsx';
import { ErrorState } from '../../ui/ErrorState.tsx';
import { LoadingState } from '../../ui/LoadingState.tsx';
import { Section } from '../../ui/Section.tsx';
import { Stat } from '../../ui/Stat.tsx';
import { DataTable } from '../../ui/table/index.ts';
import { useStudioHealth, type StudioHealth as Health } from './api.ts';
import { Verdict } from './cells.tsx';
import { jobColumns, nodeColumns, replicaColumns } from './columns.ts';
import classes from './Settings.module.css';

const jobKey = (j: Health['jobs'][number]) => j.name;
const replicaKey = (r: Health['replicas'][number]) => r.id;
const nodeKey = (n: Health['nodes'][number]) => `${n.clusterId}/${n.name}`;

/** Why a figure is missing, written out: it is a reading the server could not take, never a zero. */
const UNREAD = 'The server could not read it.';

function Loaded({ health }: Readonly<{ health: Health }>) {
  const now = useServerNow(5_000);
  const answering = health.replicas.find((r) => r.id === health.answeringReplica)?.host;
  const answeringNote = answering ? ` (${answering})` : '';
  const pool = health.dbPool;
  return (
    <Stack gap="xl">
      <Section
        title="Overall"
        headingLevel={3}
        variant="card"
        description={
          health.degraded
            ? 'A job has stalled, a broker node is failing, or a replica is gone or draining; the rows marked Degraded say which.'
            : 'Every job is on schedule and every broker node answered its latest call.'
        }
      >
        <div>
          <Verdict degraded={health.degraded} />
        </div>
      </Section>

      <Section title="Replicas" headingLevel={3}>
        <DataTable
          variant="static"
          label="Replicas"
          columns={replicaColumns()}
          data={health.replicas}
          rowKey={replicaKey}
          empty={
            <EmptyState
              kind="empty"
              title="No replicas have checked in recently"
              description="Each Studio process registers itself here once it starts."
            />
          }
        />
      </Section>

      <Section title="Background jobs" headingLevel={3}>
        <DataTable
          variant="static"
          label="Background jobs"
          columns={jobColumns(now)}
          data={health.jobs}
          rowKey={jobKey}
          empty={
            <EmptyState
              kind="empty"
              title="No background jobs are registered"
              description="Jobs poll the brokers and keep Studio’s own data tidy; they appear here once their modules start."
            />
          }
        />
      </Section>

      <Section
        title="Broker nodes"
        headingLevel={3}
        description={`Call figures are as seen from this replica${answeringNote}; another replica may see a node differently.`}
      >
        <DataTable
          variant="static"
          label="Broker nodes"
          columns={nodeColumns(now)}
          data={health.nodes}
          rowKey={nodeKey}
          empty={
            <EmptyState
              kind="empty"
              title="No broker nodes are registered"
              description="So Studio has made no management calls. Add a cluster and its nodes will be measured here."
            />
          }
        />
      </Section>

      <Section
        title="Resources"
        headingLevel={3}
        description="The database pool and the open event streams are this replica’s own."
      >
        <div className={classes.stats}>
          <Stat
            label="Database connections in use"
            value={pool.active?.toLocaleString() ?? null}
            unavailableReason={UNREAD}
          />
          <Stat
            label="Database connections idle"
            value={pool.idle?.toLocaleString() ?? null}
            unavailableReason={UNREAD}
          />
          <Stat
            label="Database pool size limit"
            value={pool.max?.toLocaleString() ?? null}
            unavailableReason={UNREAD}
          />
          <Stat
            label="Callers waiting for a connection"
            value={pool.pending?.toLocaleString() ?? null}
            unavailableReason={UNREAD}
          />
          <Stat
            label="Open event streams"
            value={health.streamClients?.toLocaleString() ?? null}
            unavailableReason={UNREAD}
          />
        </div>
      </Section>
    </Stack>
  );
}

/** Studio's own health: replicas, jobs, broker calls, the database pool and event streams, polled by the query. */
export function StudioHealth() {
  const health = useStudioHealth();

  if (health.isError) {
    return <ErrorState error={health.error} onRetry={() => void health.refetch()} />;
  }
  // The final layout is several tables tall; the frame holds that height so the sections do not push anything.
  if (health.isPending) return <LoadingState label="Loading Studio health" blockSize="40rem" />;
  return <Loaded health={health.data} />;
}
