import type { ReactNode } from 'react';
import { Alert, Anchor, Skeleton, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from '@tanstack/react-router';

import { absoluteLabel } from '../../kernel/time/time.ts';
import { useDisplayZone } from '../../kernel/time/timezone.ts';
import { VirtualTable, type GridColumn } from '../../ui/VirtualTable.tsx';
import { useBulkRuns, type BulkRunView } from './api.ts';
import { OPERATIONS, runStatus } from './words.ts';

/** The run table's columns; each run's time is a link into it, so it is reachable from the keyboard. */
function runColumns(clusterId: string): GridColumn<BulkRunView>[] {
  return [
    {
      id: 'when',
      header: 'When',
      accessor: (r) => absoluteLabel(r.startedAt ?? r.createdAt),
      // A real link, so each run is reachable from the keyboard.
      cell: (r) => (
        <Anchor component={Link} to={`/clusters/${clusterId}/bulk/${r.id}`} size="sm">
          {absoluteLabel(r.startedAt ?? r.createdAt)}
        </Anchor>
      ),
      width: 210,
    },
    { id: 'operation', header: 'Operation', accessor: (r) => OPERATIONS[r.operation].verb, width: 110 },
    { id: 'queues', header: 'Queues', accessor: (r) => r.total, numeric: true, width: 90 },
    { id: 'user', header: 'Run by', accessor: (r) => r.username, width: 160 },
    { id: 'outcome', header: 'Outcome', accessor: (r) => runStatus(r.status).text },
  ];
}

/** What stands in for the table while runs load or fail to load. */
function runsNotice(query: ReturnType<typeof useBulkRuns>): ReactNode {
  if (query.isError) {
    return (
      <Alert color="red" variant="light" title={query.error.title}>
        {query.error.message}
      </Alert>
    );
  }
  return query.data ? null : <Skeleton height={160} />;
}

/** Past and running bulk runs on a cluster, newest first. */
export function BulkRunsView() {
  useDisplayZone();
  const { clusterId } = useParams({ strict: false }) as { clusterId: string };
  const query = useBulkRuns(clusterId);

  return (
    <Stack gap="sm">
      <Title order={3}>Bulk runs</Title>
      {runsNotice(query) ?? (
        <VirtualTable
          label="Bulk runs"
          storageKey="bulk.runs"
          columns={runColumns(clusterId)}
          data={query.data ?? []}
          rowKey={(r) => r.id}
          emptyLabel={
            <Stack gap={4} align="flex-start">
              <Text fw={600}>No bulk runs yet</Text>
              <Text size="sm">
                A bulk run pauses, resumes, purges or deletes many queues at once, one queue at a time, after a preview
                you confirm. Select queues on the Queues screen to start one.
              </Text>
              <Anchor component={Link} to={`/clusters/${clusterId}/queues`} size="sm">
                Go to Queues
              </Anchor>
            </Stack>
          }
        />
      )}
    </Stack>
  );
}
